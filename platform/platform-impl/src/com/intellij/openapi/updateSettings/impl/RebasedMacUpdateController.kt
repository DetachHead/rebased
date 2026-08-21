// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.CommonBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ide.IdeBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Version
import com.intellij.util.Restarter
import com.intellij.util.system.OS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@ApiStatus.Internal
object RebasedMacUpdateController {
  @JvmStatic
  fun canPrepare(platformUpdate: PlatformUpdates.Loaded): Boolean =
    canPrepare(
      platformUpdate,
      ExternalUpdateManager.ACTUAL,
      OS.CURRENT,
      ApplicationManager.getApplication().isRestartCapable,
    )

  internal fun canPrepare(
    platformUpdate: PlatformUpdates.Loaded,
    manager: ExternalUpdateManager?,
    os: OS,
    restartCapable: Boolean,
  ): Boolean {
    if (os != OS.macOS || !restartCapable || platformUpdate.patches != null) return false
    if (manager == ExternalUpdateManager.BREW) return true
    if (manager != null) return false

    val downloadUrl = platformUpdate.newBuild.downloadUrl ?: return false
    val downloadPath = runCatching { java.net.URI(downloadUrl).path }.getOrNull()
    return isAbsoluteHttpsUri(downloadUrl) &&
           downloadPath?.endsWith(".dmg", ignoreCase = true) == true &&
           platformUpdate.newBuild.downloadDigest != null
  }

  @JvmStatic
  fun isExternalManagerBlocking(manager: ExternalUpdateManager?): Boolean =
    isExternalManagerBlocking(
      manager,
      OS.CURRENT,
      ApplicationManager.getApplication().isRestartCapable,
    )

  internal fun isExternalManagerBlocking(
    manager: ExternalUpdateManager?,
    os: OS,
    restartCapable: Boolean,
  ): Boolean =
    manager != null &&
    (manager != ExternalUpdateManager.BREW || os != OS.macOS || !restartCapable)

  @JvmStatic
  fun prepareAndOfferRestart(project: Project?, platformUpdate: PlatformUpdates.Loaded) {
    productionWorkflow.prepareAndOfferRestart(project, platformUpdate)
  }

  @JvmStatic
  suspend fun restoreNotifications(project: Project) {
    if (OS.CURRENT != OS.macOS) return

    restoreRebasedMacUpdateNotifications(
      read = {
        withContext(Dispatchers.IO) {
          val store = RebasedMacUpdateStore(updateRoot())
          readRebasedMacUpdateRestoration(
            store = store,
            currentVersion = ApplicationInfo.getInstance().fullVersion,
            currentApp = PathManager.getHomeDir().parent,
            reportFailure = { LOG.warn("Failed to restore Rebased update state", it) },
          )
        }
      },
      notify = { restoration ->
        withContext(Dispatchers.EDT) {
          productionWorkflow.restoreNotifications(project, restoration.result, restoration.prepared)
        }
      },
      reportFailure = { LOG.warn("Failed to restore Rebased update notifications", it) },
    )
  }

  private val productionWorkflow by lazy {
    RebasedMacUpdateWorkflow(productionDependencies())
  }

  private fun productionDependencies(): RebasedMacUpdateWorkflowDependencies {
    val updateRoot = updateRoot()
    val store = RebasedMacUpdateStore(updateRoot)
    val operations = DefaultRebasedMacUpdateOperations()

    return RebasedMacUpdateWorkflowDependencies(
      runBackground = { project, task ->
        ProgressManager.getInstance().run(
          object : Task.Backgroundable(
            project,
            IdeBundle.message("rebased.mac.update.preparing"),
            true,
            PerformInBackgroundOption.DEAF,
          ) {
            override fun run(indicator: ProgressIndicator) {
              task(indicator)
            }
          }
        )
      },
      invokeLater = { ApplicationManager.getApplication().invokeLater(it) },
      prepare = { build, channel, indicator ->
        val detector = RebasedMacInstallationDetector.default(
          RebasedCommandRunner { command ->
            operations.run(command, indicator, SOURCE_DETECTION_TIMEOUT_MILLIS)
          }
        )
        val preparer = RebasedMacUpdatePreparer(store, operations, detector::detect)
        preparer.prepare(build, channel, indicator)
      },
      loadFreshDigest = { version, indicator ->
        UpdateCheckerFacade.getInstance().loadFreshRebasedMacDigest(version, indicator)
      },
      buildCommand = { prepared, targetApp, trustedDigest, indicator, elevate ->
        val preparer = RebasedMacUpdatePreparer(
          store = store,
          operations = operations,
          sourceDetector = {
            throw IllegalStateException("Installation source detection is not available while restoring an update")
          },
        )
        RebasedMacUpdateInstaller(updateRoot, preparer).command(
          prepared,
          targetApp,
          trustedDigest,
          indicator,
          elevate,
        )
      },
      targetApp = { PathManager.getHomeDir().parent },
      isWritable = Files::isWritable,
      canRestart = { ApplicationManager.getApplication().isRestartCapable },
      showReady = { project, prepared ->
        val answer = Messages.showYesNoDialog(
          project,
          IdeBundle.message("rebased.mac.update.ready.message", prepared.version),
          IdeBundle.message("rebased.mac.update.ready.title"),
          IdeBundle.message("rebased.mac.update.restart.install"),
          IdeBundle.message("rebased.mac.update.later"),
          Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) RebasedMacReadyChoice.RESTART_AND_INSTALL else RebasedMacReadyChoice.LATER
      },
      showReadyNotification = { project, prepared, install ->
        UpdateCheckerFacade.getInstance().getNotificationGroupForIdeUpdateResults()
          .createNotification(
            IdeBundle.message("rebased.mac.update.ready.title"),
            IdeBundle.message("rebased.mac.update.ready.message", prepared.version),
            NotificationType.INFORMATION,
          )
          .addAction(
            NotificationAction.createSimpleExpiring(
              IdeBundle.message("rebased.mac.update.restart.install"),
              install,
            )
          )
          .setDisplayId("rebased.update.ready")
          .notify(project)
      },
      showStartupSuccess = { project ->
        UpdateCheckerFacade.getInstance().getNotificationGroupForIdeUpdateResults()
          .createNotification(
            IdeBundle.message("rebased.mac.update.success.title"),
            IdeBundle.message("rebased.mac.update.success.message"),
            NotificationType.INFORMATION,
          )
          .setDisplayId("rebased.update.success")
          .notify(project)
      },
      showStartupFailure = { project, error, retry, openRelease ->
        val notification = UpdateCheckerFacade.getInstance().getNotificationGroupForIdeUpdateResults()
          .createNotification(
            IdeBundle.message("rebased.mac.update.failed.title"),
            IdeBundle.message(
              "rebased.mac.update.failed.message",
              error.message ?: error.javaClass.simpleName,
            ),
            NotificationType.ERROR,
          )
        if (retry != null) {
          notification.addAction(
            NotificationAction.createSimpleExpiring(
              IdeBundle.message("rebased.mac.update.retry"),
              retry,
            )
          )
        }
        if (openRelease != null) {
          notification.addAction(
            NotificationAction.createSimpleExpiring(
              IdeBundle.message("rebased.mac.update.open.release"),
              openRelease,
            )
          )
        }
        notification
          .setDisplayId("rebased.update.failure")
          .notify(project)
      },
      showFailure = { project, error ->
        when (
          Messages.showDialog(
            project,
            IdeBundle.message(
              "rebased.mac.update.failed.message",
              error.message ?: error.javaClass.simpleName,
            ),
            IdeBundle.message("rebased.mac.update.failed.title"),
            arrayOf(
              IdeBundle.message("rebased.mac.update.retry"),
              IdeBundle.message("rebased.mac.update.open.release"),
              CommonBundle.getCancelButtonText(),
            ),
            0,
            Messages.getErrorIcon(),
          )
        ) {
          0 -> RebasedMacFailureChoice.RETRY
          1 -> RebasedMacFailureChoice.OPEN_RELEASE_PAGE
          else -> RebasedMacFailureChoice.CANCEL
        }
      },
      openReleasePage = BrowserUtil::browse,
      restart = { command ->
        Restarter.setCopyRestarterFiles()
        (ApplicationManager.getApplication() as ApplicationEx).restart(
          ApplicationEx.EXIT_CONFIRMED or ApplicationEx.SAVE,
          command.toTypedArray(),
        )
      },
    )
  }

  private fun updateRoot(): Path = PathManager.getSystemDir().resolve(UPDATE_ROOT_NAME)
}

internal suspend fun restoreRebasedMacUpdateNotifications(
  read: suspend () -> RebasedMacUpdateRestoration,
  notify: suspend (RebasedMacUpdateRestoration) -> Unit,
  reportFailure: (Throwable) -> Unit,
) {
  withContext(NonCancellable) {
    try {
      notify(read())
    }
    catch (error: Exception) {
      error.rethrowIfCancellation()
      reportFailure(error)
    }
  }
}

internal data class RebasedMacUpdateRestoration(
  val result: RebasedMacInstallResult,
  val prepared: PreparedRebasedMacUpdate?,
)

internal fun readRebasedMacUpdateRestoration(
  store: RebasedMacUpdateStore,
  currentVersion: String,
  currentApp: Path,
  reportFailure: (Throwable) -> Unit = {},
): RebasedMacUpdateRestoration {
  val result = store.consumeInstallResult(currentVersion, currentApp)
  val hadPreparedState = store.hasPreparedState()
  val prepared = store.load()
  if (prepared == null) {
    if (hadPreparedState) {
      reportFailure(IllegalStateException("Discarding invalid prepared Rebased update state"))
      try {
        store.clear()
      }
      catch (error: Exception) {
        reportFailure(error)
      }
    }
    clearStaleUpdateData(store, null, reportFailure, retainPreparedState = false)
    return RebasedMacUpdateRestoration(result, null)
  }

  val runningVersion = parseRebasedVersion(currentVersion)
  if (runningVersion == null) {
    reportFailure(IllegalStateException("Cannot parse the running Rebased version: $currentVersion"))
    return RebasedMacUpdateRestoration(result, null)
  }
  val preparedVersion = parseRebasedVersion(prepared.version)
  if (result is RebasedMacInstallResult.Failed && preparedVersion == runningVersion) {
    clearStaleUpdateData(store, prepared, reportFailure)
    return RebasedMacUpdateRestoration(result, prepared)
  }
  if (preparedVersion == null || preparedVersion <= runningVersion) {
    if (preparedVersion == null) {
      reportFailure(IllegalStateException("Discarding malformed prepared Rebased update version: ${prepared.version}"))
    }
    try {
      store.discardPreparedState(prepared, currentApp)
    }
    catch (error: Exception) {
      reportFailure(error)
    }
    clearStaleUpdateData(store, null, reportFailure, retainPreparedState = false)
    return RebasedMacUpdateRestoration(result, null)
  }
  clearStaleUpdateData(store, prepared, reportFailure)
  return RebasedMacUpdateRestoration(result, prepared)
}

private fun clearStaleUpdateData(
  store: RebasedMacUpdateStore,
  prepared: PreparedRebasedMacUpdate?,
  reportFailure: (Throwable) -> Unit,
  retainPreparedState: Boolean = true,
) {
  val retainedVersion = prepared
    ?.takeIf { it.strategy == RebasedMacUpdateStrategy.DIRECT }
    ?.version
  try {
    store.clearStaleData(retainedVersion, retainPreparedState)
  }
  catch (error: Exception) {
    reportFailure(error)
  }
}

private fun parseRebasedVersion(value: String): Version? =
  value.takeIf(REBASED_VERSION_PATTERN::matches)?.let(Version::parseVersion)

internal enum class RebasedMacReadyChoice {
  RESTART_AND_INSTALL,
  LATER,
}

internal enum class RebasedMacFailureChoice {
  RETRY,
  OPEN_RELEASE_PAGE,
  CANCEL,
}

internal data class RebasedMacUpdateWorkflowDependencies(
  val runBackground: (Project?, (ProgressIndicator) -> Unit) -> Unit,
  val invokeLater: (() -> Unit) -> Unit,
  val prepare: (BuildInfo, UpdateChannel, ProgressIndicator) -> PreparedRebasedMacUpdate,
  val loadFreshDigest: (String, ProgressIndicator) -> String?,
  val buildCommand: (PreparedRebasedMacUpdate, Path, String?, ProgressIndicator, Boolean) -> List<String>,
  val targetApp: () -> Path,
  val isWritable: (Path) -> Boolean,
  val canRestart: () -> Boolean,
  val showReady: (Project?, PreparedRebasedMacUpdate) -> RebasedMacReadyChoice,
  val showReadyNotification: (Project?, PreparedRebasedMacUpdate, () -> Unit) -> Unit,
  val showStartupSuccess: (Project?) -> Unit,
  val showStartupFailure: (Project?, Throwable, (() -> Unit)?, (() -> Unit)?) -> Unit,
  val showFailure: (Project?, Throwable) -> RebasedMacFailureChoice,
  val openReleasePage: (String) -> Unit,
  val restart: (List<String>) -> Unit,
)

internal class RebasedMacUpdateWorkflow(
  private val dependencies: RebasedMacUpdateWorkflowDependencies,
) {
  private val lock = Any()
  private var generation = 0L
  private var phase: Phase = Phase.Idle
  private var pending: Pending? = null
  private val restoredNotificationGenerations = mutableSetOf<Long>()
  private var terminalRestoreNotificationShown = false

  fun prepareAndOfferRestart(project: Project?, platformUpdate: PlatformUpdates.Loaded) {
    if (!dependencies.canRestart()) {
      dependencies.invokeLater {
        handleFailure(
          project = project,
          error = restartUnavailable(),
          releasePage = platformUpdate.updatedChannel.url
                        ?: platformUpdate.newBuild.blogPost
                        ?: platformUpdate.newBuild.downloadUrl,
          retry = { prepareAndOfferRestart(project, platformUpdate) },
        )
      }
      return
    }
    val next = synchronized(lock) {
      when (val current = phase) {
        Phase.Idle -> startPreparing(Pending(project, platformUpdate))
        is Phase.Preparing -> {
          val request = Pending(project, platformUpdate)
          if (shouldReplacePending(current.version, pending, request)) {
            pending = request
          }
          Next.None
        }
        is Phase.Installing -> Next.None
        is Phase.Prepared -> {
          if (current.update.version == platformUpdate.newBuild.version) {
            Next.OfferReady(current.generation, current.update, project)
          }
          else if (isNewerVersion(platformUpdate.newBuild.version, current.update.version)) {
            startPreparing(Pending(project, platformUpdate))
          }
          else {
            Next.OfferReady(current.generation, current.update, project)
          }
        }
      }
    }
    execute(next)
  }

  fun restoreNotifications(
    project: Project?,
    result: RebasedMacInstallResult,
    prepared: PreparedRebasedMacUpdate?,
  ) {
    val next = synchronized(lock) {
      when (result) {
        RebasedMacInstallResult.Success -> {
          if (terminalRestoreNotificationShown) RestoreNext.None
          else {
            terminalRestoreNotificationShown = true
            RestoreNext.ShowSuccess
          }
        }
        is RebasedMacInstallResult.Failed -> {
          val adopted = prepared?.let(::adoptPrepared)
          if (adopted != null) {
            if (!restoredNotificationGenerations.add(adopted.generation)) RestoreNext.None
            else RestoreNext.ShowFailure(result.message, adopted)
          }
          else if (terminalRestoreNotificationShown) {
            RestoreNext.None
          }
          else {
            terminalRestoreNotificationShown = true
            RestoreNext.ShowFailure(result.message, null)
          }
        }
        RebasedMacInstallResult.None -> {
          val adopted = prepared?.let(::adoptPrepared) ?: return@synchronized RestoreNext.None
          if (!restoredNotificationGenerations.add(adopted.generation)) RestoreNext.None
          else RestoreNext.ShowReady(adopted)
        }
      }
    }

    when (next) {
      RestoreNext.None -> Unit
      RestoreNext.ShowSuccess -> dependencies.showStartupSuccess(project)
      is RestoreNext.ShowReady -> {
        val restored = next.prepared
        dependencies.showReadyNotification(project, restored.update) {
          install(project, restored.update, restored.generation)
        }
      }
      is RestoreNext.ShowFailure -> {
        val restored = next.prepared
        dependencies.showStartupFailure(
          project,
          IllegalStateException(next.message),
          restored?.let {
            { install(project, it.update, it.generation) }
          },
          restored?.let {
            { dependencies.openReleasePage(it.update.releasePageUrl) }
          },
        )
      }
    }
  }

  private fun adoptPrepared(prepared: PreparedRebasedMacUpdate): Phase.Prepared? {
    return when (val current = phase) {
      Phase.Idle -> Phase.Prepared(++generation, prepared).also { phase = it }
      is Phase.Prepared -> current.takeIf { it.update == prepared }
      is Phase.Preparing, is Phase.Installing -> null
    }
  }

  private fun startPreparing(request: Pending): Next.Prepare {
    val nextGeneration = ++generation
    phase = Phase.Preparing(nextGeneration, request.platformUpdate.newBuild.version)
    return Next.Prepare(nextGeneration, request)
  }

  private fun shouldReplacePending(
    activeVersion: String,
    currentPending: Pending?,
    request: Pending,
  ): Boolean {
    if (!isNewerVersion(request.platformUpdate.newBuild.version, activeVersion)) return false
    val pendingVersion = currentPending?.platformUpdate?.newBuild?.version ?: return true
    return isNewerVersion(request.platformUpdate.newBuild.version, pendingVersion)
  }

  private fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateVersion = parseRebasedVersion(candidate) ?: return false
    val currentVersion = parseRebasedVersion(current) ?: return false
    return candidateVersion > currentVersion
  }

  private fun prepare(
    project: Project?,
    platformUpdate: PlatformUpdates.Loaded,
    expectedGeneration: Long,
  ) {
    dependencies.runBackground(null) { indicator ->
      try {
        val prepared = dependencies.prepare(
          platformUpdate.newBuild,
          platformUpdate.updatedChannel,
          indicator,
        )
        indicator.checkCanceled()
        val next = synchronized(lock) {
          val current = phase
          if (current is Phase.Preparing && current.generation == expectedGeneration) {
            nextAfterPreparation {
              phase = Phase.Prepared(expectedGeneration, prepared)
              Next.OfferReady(expectedGeneration, prepared, project)
            }
          }
          else {
            Next.None
          }
        }
        execute(next)
      }
      catch (error: Throwable) {
        val next = synchronized(lock) {
          val current = phase
          if (current is Phase.Preparing && current.generation == expectedGeneration) {
            nextAfterPreparation {
              phase = Phase.Idle
              Next.ShowFailure(expectedGeneration, project, platformUpdate, error)
            }
          }
          else {
            Next.None
          }
        }
        if ((error is ProcessCanceledException || error is CancellationException) && next is Next.Prepare) {
          execute(next)
        }
        error.rethrowIfCancellation()
        execute(next)
      }
    }
  }

  private fun nextAfterPreparation(noPending: () -> Next): Next {
    val request = pending ?: return noPending()
    pending = null
    return startPreparing(request)
  }

  private fun execute(next: Next) {
    when (next) {
      Next.None -> Unit
      is Next.OfferReady -> offerReady(next.project, next.update, next.generation)
      is Next.Prepare -> prepare(next.request.project, next.request.platformUpdate, next.generation)
      is Next.ShowFailure -> dependencies.invokeLater {
        if (!isIdleGeneration(next.generation)) return@invokeLater
        val platformUpdate = next.platformUpdate
        handleFailure(
          project = next.project,
          error = next.error,
          releasePage = platformUpdate.updatedChannel.url
                        ?: platformUpdate.newBuild.blogPost
                        ?: platformUpdate.newBuild.downloadUrl,
          retry = { prepareAndOfferRestart(next.project, platformUpdate) },
        )
      }
    }
  }

  private fun offerReady(
    project: Project?,
    prepared: PreparedRebasedMacUpdate,
    expectedGeneration: Long,
  ) {
    dependencies.invokeLater {
      if (!isPrepared(expectedGeneration, prepared)) return@invokeLater
      when (dependencies.showReady(project, prepared)) {
        RebasedMacReadyChoice.RESTART_AND_INSTALL -> install(project, prepared, expectedGeneration)
        RebasedMacReadyChoice.LATER -> {
          dependencies.showReadyNotification(project, prepared) {
            install(project, prepared, expectedGeneration)
          }
        }
      }
    }
  }

  private fun install(
    project: Project?,
    prepared: PreparedRebasedMacUpdate,
    expectedGeneration: Long,
  ) {
    if (!isPrepared(expectedGeneration, prepared)) return
    if (!dependencies.canRestart()) {
      handleFailure(
        project = project,
        error = restartUnavailable(),
        releasePage = prepared.releasePageUrl,
        retry = { install(project, prepared, expectedGeneration) },
      )
      return
    }
    val accepted = synchronized(lock) {
      val current = phase
      if (current is Phase.Prepared &&
          current.generation == expectedGeneration &&
          current.update == prepared) {
        phase = Phase.Installing(expectedGeneration, prepared)
        true
      }
      else {
        false
      }
    }
    if (!accepted) return
    dependencies.runBackground(null) { indicator ->
      try {
        val trustedDigest = when (prepared.strategy) {
          RebasedMacUpdateStrategy.DIRECT -> {
            dependencies.loadFreshDigest(prepared.version, indicator)
              ?: throw RebasedMacUpdateException.Verification(
                IdeBundle.message("rebased.mac.update.fresh.metadata.invalid"),
              )
          }
          RebasedMacUpdateStrategy.HOMEBREW -> null
        }
        val targetApp = dependencies.targetApp()
        val elevate = prepared.strategy == RebasedMacUpdateStrategy.DIRECT &&
                      !dependencies.isWritable(targetApp.parent)
        val command = dependencies.buildCommand(
          prepared,
          targetApp,
          trustedDigest,
          indicator,
          elevate,
        )
        indicator.checkCanceled()
        dependencies.invokeLater {
          if (indicator.isCanceled) {
            restorePrepared(expectedGeneration, prepared)
            return@invokeLater
          }
          if (!isInstalling(expectedGeneration, prepared)) return@invokeLater
          if (dependencies.canRestart()) {
            dependencies.restart(command)
          }
          else {
            restorePrepared(expectedGeneration, prepared)
            handleFailure(
              project = project,
              error = restartUnavailable(),
              releasePage = prepared.releasePageUrl,
              retry = { install(project, prepared, expectedGeneration) },
            )
          }
        }
      }
      catch (error: Throwable) {
        val restored = restorePrepared(expectedGeneration, prepared)
        error.rethrowIfCancellation()
        if (restored) dependencies.invokeLater {
          if (isPrepared(expectedGeneration, prepared)) {
            handleFailure(
              project = project,
              error = error,
              releasePage = prepared.releasePageUrl,
              retry = { install(project, prepared, expectedGeneration) },
            )
          }
        }
      }
    }
  }

  private fun isIdleGeneration(expectedGeneration: Long): Boolean =
    synchronized(lock) {
      generation == expectedGeneration && phase == Phase.Idle
    }

  private fun isPrepared(expectedGeneration: Long, prepared: PreparedRebasedMacUpdate): Boolean =
    synchronized(lock) {
      val current = phase
      current is Phase.Prepared &&
      current.generation == expectedGeneration &&
      current.update == prepared
    }

  private fun isInstalling(expectedGeneration: Long, prepared: PreparedRebasedMacUpdate): Boolean =
    synchronized(lock) {
      val current = phase
      current is Phase.Installing &&
      current.generation == expectedGeneration &&
      current.update == prepared
    }

  private fun restorePrepared(expectedGeneration: Long, prepared: PreparedRebasedMacUpdate): Boolean =
    synchronized(lock) {
      val current = phase
      if (current is Phase.Installing &&
          current.generation == expectedGeneration &&
          current.update == prepared) {
        phase = Phase.Prepared(expectedGeneration, prepared)
        true
      }
      else {
        false
      }
    }

  private fun handleFailure(
    project: Project?,
    error: Throwable,
    releasePage: String?,
    retry: () -> Unit,
  ) {
    when (dependencies.showFailure(project, error)) {
      RebasedMacFailureChoice.RETRY -> retry()
      RebasedMacFailureChoice.OPEN_RELEASE_PAGE -> releasePage?.let(dependencies.openReleasePage)
      RebasedMacFailureChoice.CANCEL -> Unit
    }
  }

  private fun restartUnavailable(): IllegalStateException =
    IllegalStateException(IdeBundle.message("rebased.mac.update.restart.unavailable"))

  private sealed interface Phase {
    data object Idle : Phase
    data class Preparing(val generation: Long, val version: String) : Phase
    data class Prepared(val generation: Long, val update: PreparedRebasedMacUpdate) : Phase
    data class Installing(val generation: Long, val update: PreparedRebasedMacUpdate) : Phase
  }

  private data class Pending(
    val project: Project?,
    val platformUpdate: PlatformUpdates.Loaded,
  )

  private sealed interface Next {
    data object None : Next
    data class Prepare(val generation: Long, val request: Pending) : Next
    data class OfferReady(
      val generation: Long,
      val update: PreparedRebasedMacUpdate,
      val project: Project?,
    ) : Next
    data class ShowFailure(
      val generation: Long,
      val project: Project?,
      val platformUpdate: PlatformUpdates.Loaded,
      val error: Throwable,
    ) : Next
  }

  private sealed interface RestoreNext {
    data object None : RestoreNext
    data object ShowSuccess : RestoreNext
    data class ShowReady(val prepared: Phase.Prepared) : RestoreNext
    data class ShowFailure(val message: String, val prepared: Phase.Prepared?) : RestoreNext
  }
}

private fun Throwable.rethrowIfCancellation() {
  if (this is ProcessCanceledException || this is CancellationException) throw this
}

private const val UPDATE_ROOT_NAME = "rebased-update"
private const val SOURCE_DETECTION_TIMEOUT_MILLIS = 60_000
private val REBASED_VERSION_PATTERN = Regex("\\d+\\.\\d+(?:\\.\\d+)?")
private val LOG = logger<RebasedMacUpdateController>()
