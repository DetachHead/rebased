// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.ide.IdeBundle
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.system.CpuArch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jdom.Element
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CancellationException

internal class RebasedMacUpdatePreparer(
  private val store: RebasedMacUpdateStore,
  private val operations: RebasedMacUpdateOperations,
  private val sourceDetector: () -> RebasedMacInstallationSource,
  private val arch: CpuArch = CpuArch.CURRENT,
  mountRoot: Path = Path.of(PathManager.getTempPath()).resolve(DEFAULT_MOUNT_ROOT_NAME),
) {
  private val mountRoot = mountRoot.toAbsolutePath().normalize()

  fun prepare(
    build: BuildInfo,
    channel: UpdateChannel,
    indicator: ProgressIndicator,
  ): PreparedRebasedMacUpdate {
    indicator.checkCanceled()
    try {
      val previous = store.load()
      if (previous == null) {
        store.clear()
      }
      else {
        store.discardPreparedState(previous)
      }
    }
    catch (failure: Throwable) {
      throw when (failure) {
        is ProcessCanceledException, is CancellationException, is RebasedMacUpdateException -> failure
        is Exception -> RebasedMacUpdateException.Preparation("Failed to prepare the Rebased update", failure)
        else -> failure
      }
    }
    when (val source = sourceDetector()) {
      RebasedMacInstallationSource.Direct -> Unit
      is RebasedMacInstallationSource.Homebrew -> {
        return prepareHomebrew(build, channel, source.executable, indicator)
      }
      RebasedMacInstallationSource.HomebrewUnavailable -> {
        throw RebasedMacUpdateException.HomebrewUnavailable(
          IdeBundle.message("rebased.mac.update.homebrew.unavailable"),
        )
      }
    }
    if (build.version.isBlank()) {
      throw RebasedMacUpdateException.Preparation("The Rebased update has no target version")
    }
    val downloadUrl = requireHttpsDmgUrl(build.downloadUrl)
    val expectedDigest = build.downloadDigest
      ?.takeIf(SHA256_PATTERN::matches)
      ?.lowercase(Locale.ROOT)
      ?: throw RebasedMacUpdateException.Preparation(
        "The Rebased update has no valid SHA-256 digest",
      )
    val releasePageUrl = build.blogPost?.takeIf(::isAbsoluteHttpsUri)
                         ?: channel.url?.takeIf(::isAbsoluteHttpsUri)
                         ?: throw RebasedMacUpdateException.Preparation(
                           "The Rebased update has no valid HTTPS release page",
                         )
    val requiredArchitecture = when (arch) {
      CpuArch.ARM64 -> "arm64"
      CpuArch.X86_64 -> "x86_64"
      else -> throw RebasedMacUpdateException.Preparation("Unsupported architecture: $arch")
    }

    val versionDirectory = store.versionDirectory(build.version)
    try {
      store.clearStaleData(build.version)
      deleteVersionDirectory(versionDirectory)
      Files.createDirectories(versionDirectory)

      val partDmg = versionDirectory.resolve(PART_DMG_NAME)
      val verifiedDmg = versionDirectory.resolve(VERIFIED_DMG_NAME)
      val stagedApp = versionDirectory.resolve(APP_NAME)

      indicator.stage(IdeBundle.message("rebased.mac.update.downloading"), indeterminate = false)
      download(downloadUrl, partDmg, indicator)

      indicator.stage(IdeBundle.message("rebased.mac.update.verifying"))
      if (calculateDigest(partDmg, indicator).lowercase(Locale.ROOT) != expectedDigest) {
        throw RebasedMacUpdateException.Verification("The downloaded Rebased DMG has an invalid SHA-256 digest")
      }
      operations.moveAtomically(partDmg, verifiedDmg)

      indicator.stage(IdeBundle.message("rebased.mac.update.mounting"))
      val mountDirectory = createMountDirectory(versionDirectory)
      var attachAttempted = false
      var attachDevice: Path? = null
      var primaryFailure: Throwable? = null
      try {
        attachAttempted = true
        runPreparationCommand(
          arguments = attachCommand(mountDirectory, verifiedDmg),
          action = "attach the Rebased DMG",
          indicator = indicator,
          timeoutMillis = SHORT_COMMAND_TIMEOUT_MILLIS,
          onResult = { attachDevice = parseAttachDevice(it.stdout) },
        )

        val mountedApp = mountDirectory.resolve(APP_NAME)
        requireRealDirectory(mountedApp, mountDirectory)

        indicator.stage(IdeBundle.message("rebased.mac.update.copying"))
        runPreparationCommand(
          arguments = listOf(DITTO, mountedApp.toString(), stagedApp.toString()),
          action = "copy the Rebased application",
          indicator = indicator,
          timeoutMillis = DITTO_TIMEOUT_MILLIS,
        )

        indicator.stage(IdeBundle.message("rebased.mac.update.validating"))
        validateBundle(stagedApp, build.version, requiredArchitecture, indicator)
      }
      catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
      }
      finally {
        if (attachAttempted) {
          var detached = false
          try {
            detachDmg(attachDevice, mountDirectory)
            detached = true
          }
          catch (detachFailure: Throwable) {
            val failure = primaryFailure
            if (failure == null) {
              if (attachDevice == null) {
                cleanupUnattachedMountDirectory(mountDirectory, detachFailure)
              }
              throw detachFailure
            }
            failure.addSuppressed(detachFailure)
            if (attachDevice == null) {
              cleanupUnattachedMountDirectory(mountDirectory, failure)
            }
            LOG.warn("Failed to detach Rebased update mount at $mountDirectory", detachFailure)
          }
          if (detached) {
            try {
              operations.deleteRecursively(mountDirectory)
            }
            catch (cleanupFailure: Throwable) {
              val reportedCleanupFailure = RebasedMacUpdateException.Preparation(
                "Failed to clean the detached Rebased DMG mount",
                cleanupFailure,
              )
              val failure = primaryFailure
              if (failure == null) {
                throw reportedCleanupFailure
              }
              failure.addSuppressed(reportedCleanupFailure)
              LOG.warn("Failed to clean detached Rebased update mount at $mountDirectory", cleanupFailure)
            }
          }
        }
      }

      indicator.stage(IdeBundle.message("rebased.mac.update.validating"))
      validateBundle(stagedApp, build.version, requiredArchitecture, indicator)
      val prepared = PreparedRebasedMacUpdate(
        version = build.version,
        strategy = RebasedMacUpdateStrategy.DIRECT,
        stagedApp = stagedApp.toRealPath(),
        verifiedDmg = verifiedDmg.toRealPath(),
        verifiedDmgSha256 = expectedDigest,
        brewExecutable = null,
        releasePageUrl = releasePageUrl,
      )
      indicator.checkCanceled()
      store.save(prepared)
      return prepared
    }
    catch (failure: Throwable) {
      val reportedFailure = when (failure) {
        is ProcessCanceledException, is CancellationException, is RebasedMacUpdateException -> failure
        is Exception -> RebasedMacUpdateException.Preparation("Failed to prepare the Rebased update", failure)
        else -> failure
      }
      cleanup(versionDirectory, reportedFailure)
      throw reportedFailure
    }
  }

  private fun prepareHomebrew(
    build: BuildInfo,
    channel: UpdateChannel,
    executable: Path,
    indicator: ProgressIndicator,
  ): PreparedRebasedMacUpdate {
    if (build.version.isBlank()) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message("rebased.mac.update.homebrew.target.version.missing"),
      )
    }
    val releasePageUrl = build.blogPost?.takeIf(::isAbsoluteHttpsUri)
                         ?: channel.url?.takeIf(::isAbsoluteHttpsUri)
                         ?: throw RebasedMacUpdateException.Preparation(
                           IdeBundle.message("rebased.mac.update.homebrew.release.page.invalid"),
                         )
    var brewExecutable = requireHomebrewExecutable(executable)

    indicator.stage(IdeBundle.message("rebased.mac.update.homebrew.refreshing"))
    runHomebrewCommand(
      arguments = listOf(brewExecutable.toString(), "update"),
      action = IdeBundle.message("rebased.mac.update.homebrew.action.update"),
      indicator = indicator,
      timeoutMillis = HOMEBREW_LONG_TIMEOUT_MILLIS,
    )

    indicator.stage(IdeBundle.message("rebased.mac.update.homebrew.checking"))
    brewExecutable = requireHomebrewExecutable(brewExecutable)
    val info = runHomebrewCommand(
      arguments = listOf(brewExecutable.toString(), "info", "--json=v2", "--cask", CASK_NAME),
      action = IdeBundle.message("rebased.mac.update.homebrew.action.info"),
      indicator = indicator,
      timeoutMillis = SHORT_COMMAND_TIMEOUT_MILLIS,
    )
    val availableVersion = parseHomebrewCaskVersion(info.stdout)
    if (availableVersion != build.version) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message(
          "rebased.mac.update.homebrew.version.mismatch",
          build.version,
          availableVersion,
        ),
      )
    }

    indicator.stage(IdeBundle.message("rebased.mac.update.homebrew.fetching"))
    brewExecutable = requireHomebrewExecutable(brewExecutable)
    runHomebrewCommand(
      arguments = listOf(brewExecutable.toString(), "fetch", "--cask", CASK_NAME),
      action = IdeBundle.message("rebased.mac.update.homebrew.action.fetch"),
      indicator = indicator,
      timeoutMillis = HOMEBREW_LONG_TIMEOUT_MILLIS,
    )

    indicator.checkCanceled()
    brewExecutable = requireHomebrewExecutable(brewExecutable)
    indicator.checkCanceled()
    val prepared = PreparedRebasedMacUpdate(
      version = build.version,
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = brewExecutable,
      releasePageUrl = releasePageUrl,
    )
    try {
      store.save(prepared)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message("rebased.mac.update.homebrew.save.failed"),
        e,
      )
    }
    return prepared
  }

  private fun requireHomebrewExecutable(executable: Path): Path {
    val normalized = executable.takeIf(Path::isAbsolute)?.normalize()
                     ?: throw RebasedMacUpdateException.HomebrewUnavailable(
                       IdeBundle.message("rebased.mac.update.homebrew.unavailable"),
                     )
    try {
      val realExecutable = normalized.toRealPath()
      if (!Files.isRegularFile(realExecutable, NOFOLLOW_LINKS) || !Files.isExecutable(realExecutable)) {
        throw RebasedMacUpdateException.HomebrewUnavailable(
          IdeBundle.message("rebased.mac.update.homebrew.unavailable"),
        )
      }
    }
    catch (e: RebasedMacUpdateException.HomebrewUnavailable) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.HomebrewUnavailable(
        IdeBundle.message("rebased.mac.update.homebrew.unavailable"),
        e,
      )
    }
    return normalized
  }

  private fun parseHomebrewCaskVersion(stdout: String): String {
    val root = try {
      Json.parseToJsonElement(stdout)
    }
    catch (e: Exception) {
      throw invalidHomebrewMetadata(e)
    }
    val casks = (root as? JsonObject)?.get("casks") as? JsonArray
                ?: throw invalidHomebrewMetadata()
    if (casks.size != 1) {
      throw invalidHomebrewMetadata()
    }
    val cask = casks.single() as? JsonObject ?: throw invalidHomebrewMetadata()
    val version = cask["version"] as? JsonPrimitive
    if (version?.isString != true) {
      throw invalidHomebrewMetadata()
    }
    return version.contentOrNull?.takeIf { it.isNotBlank() }
           ?: throw invalidHomebrewMetadata()
  }

  private fun invalidHomebrewMetadata(cause: Throwable? = null): RebasedMacUpdateException.Preparation =
    RebasedMacUpdateException.Preparation(
      IdeBundle.message("rebased.mac.update.homebrew.metadata.invalid"),
      cause,
    )

  private fun runHomebrewCommand(
    arguments: List<String>,
    action: String,
    indicator: ProgressIndicator,
    timeoutMillis: Int,
  ): RebasedCommandResult {
    val result = try {
      run(arguments, indicator, timeoutMillis)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message("rebased.mac.update.homebrew.command.start.failed", action),
        e,
      )
    }
    val diagnostics = commandDiagnostics(result)
    if (result.timedOut) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message("rebased.mac.update.homebrew.command.timed.out", action, diagnostics),
      )
    }
    if (result.exitCode != 0) {
      throw RebasedMacUpdateException.Preparation(
        IdeBundle.message(
          "rebased.mac.update.homebrew.command.failed",
          action,
          result.exitCode,
          diagnostics,
        ),
      )
    }
    return result
  }

  private fun commandDiagnostics(result: RebasedCommandResult): String =
    IdeBundle.message(
      "rebased.mac.update.homebrew.command.diagnostics",
      boundedCommandOutput(result.stderr, MAX_STDERR_COMMAND_OUTPUT_LENGTH),
      boundedCommandOutput(result.stdout, MAX_STDOUT_COMMAND_OUTPUT_LENGTH),
    )

  private fun boundedCommandOutput(output: String, maxLength: Int): String {
    val truncationMarker = IdeBundle.message("rebased.mac.update.homebrew.command.output.truncated")
    val bounded = if (output.length <= maxLength) {
      output
    }
    else {
      val excerptLength = maxLength - truncationMarker.length
      val headLength = excerptLength / 3
      val tailLength = excerptLength - headLength
      output.take(headLength) + truncationMarker + output.takeLast(tailLength)
    }
    val sanitized = bounded
      .map { if (it.isISOControl()) ' ' else it }
      .joinToString("")
      .trim()
    return sanitized.ifEmpty {
      IdeBundle.message("rebased.mac.update.homebrew.command.output.empty")
    }
  }

  /**
   * Revalidates the only artifact authorized for a direct installation.
   *
   * Task 6/7 callers must freshly fetch the GitHub release metadata and pass its
   * [BuildInfo.downloadDigest] immediately before building the external direct-install command.
   * The installer must then remount the returned DMG and copy the application from that mount;
   * it must never install [PreparedRebasedMacUpdate.stagedApp]. The persisted
   * [PreparedRebasedMacUpdate.verifiedDmgSha256] is untrusted cache metadata and is checked only
   * for consistency with [trustedSha256].
   *
   * @param trustedSha256 the exact 64-hex SHA-256 from the freshly fetched GitHub release metadata
   * @return the canonical retained DMG after hashing it against [trustedSha256]
   */
  fun revalidateVerifiedDmg(
    prepared: PreparedRebasedMacUpdate,
    trustedSha256: String,
    indicator: ProgressIndicator,
  ): Path {
    val trustedDigest = trustedSha256
      .takeIf(SHA256_PATTERN::matches)
      ?.lowercase(Locale.ROOT)
      ?: throw RebasedMacUpdateException.Verification("The trusted Rebased DMG SHA-256 digest is invalid")
    indicator.stage(IdeBundle.message("rebased.mac.update.verifying"))
    val validatedPrepared = try {
      store.validate(prepared)
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("The prepared Rebased DMG path is no longer valid", e)
    }
    if (validatedPrepared?.strategy != RebasedMacUpdateStrategy.DIRECT) {
      throw RebasedMacUpdateException.Verification("The prepared Rebased update has no valid retained DMG")
    }
    val canonicalState = store.load()
                         ?: throw RebasedMacUpdateException.Verification("The prepared Rebased update state is no longer valid")
    if (canonicalState != validatedPrepared) {
      throw RebasedMacUpdateException.Verification("The prepared Rebased update no longer matches the current stored state")
    }
    val verifiedDmg = canonicalState.verifiedDmg
                      ?: throw RebasedMacUpdateException.Verification("The prepared Rebased update has no retained DMG")
    val expectedDmg = store.versionDirectory(canonicalState.version).resolve(VERIFIED_DMG_NAME)
    val canonicalExpectedDmg = try {
      if (!Files.isRegularFile(expectedDmg, NOFOLLOW_LINKS) || Files.isSymbolicLink(expectedDmg)) {
        throw IOException("The canonical retained Rebased DMG is missing or symbolic")
      }
      expectedDmg.toRealPath()
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("The canonical retained Rebased DMG path is no longer valid", e)
    }
    if (verifiedDmg != canonicalExpectedDmg) {
      throw RebasedMacUpdateException.Verification("The prepared Rebased update does not reference the canonical retained DMG")
    }
    if (canonicalState.verifiedDmgSha256 != trustedDigest) {
      throw RebasedMacUpdateException.Verification("The cached Rebased DMG digest does not match trusted release metadata")
    }
    if (calculateDigest(verifiedDmg, indicator).lowercase(Locale.ROOT) != trustedDigest) {
      throw RebasedMacUpdateException.Verification("The prepared Rebased DMG does not match trusted release metadata")
    }
    return verifiedDmg
  }

  private fun download(url: String, target: Path, indicator: ProgressIndicator) {
    try {
      operations.download(url, target, indicator)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: IOException) {
      throw RebasedMacUpdateException.Download("Failed to download the Rebased DMG", e)
    }
  }

  private fun calculateDigest(path: Path, indicator: ProgressIndicator): String {
    return try {
      operations.sha256(path, indicator)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("Failed to calculate the downloaded Rebased DMG digest", e)
    }
  }

  private fun validateBundle(
    stagedApp: Path,
    version: String,
    requiredArchitecture: String,
    indicator: ProgressIndicator,
  ) {
    val contents = stagedApp.resolve("Contents")
    val macOs = contents.resolve("MacOS")
    val infoPlist = contents.resolve("Info.plist")
    val executable = macOs.resolve(BUNDLE_EXECUTABLE)
    requireNoSymlinkComponents(stagedApp, contents, macOs, infoPlist, executable)

    val realStagedApp = requireRealDirectory(stagedApp, stagedApp.parent)
    requireRealFile(infoPlist, realStagedApp)
    requireRealFile(executable, realStagedApp)

    val bundleIdentifier = plistValue("CFBundleIdentifier", infoPlist, indicator)
    if (bundleIdentifier != BUNDLE_ID) {
      throw RebasedMacUpdateException.Verification("The staged application has an invalid bundle identifier: $bundleIdentifier")
    }
    val bundleVersion = plistValue("CFBundleShortVersionString", infoPlist, indicator)
    if (bundleVersion != version) {
      throw RebasedMacUpdateException.Verification(
        "The staged application version $bundleVersion does not match the update version $version",
      )
    }
    val bundleExecutable = plistValue("CFBundleExecutable", infoPlist, indicator)
    if (bundleExecutable != BUNDLE_EXECUTABLE) {
      throw RebasedMacUpdateException.Verification(
        "The staged application executable $bundleExecutable does not match $BUNDLE_EXECUTABLE",
      )
    }
    if (!Files.isExecutable(executable)) {
      throw RebasedMacUpdateException.Verification("The staged application binary is not executable: $executable")
    }
    val architectures = runVerificationCommand(
      listOf(LIPO, "-archs", executable.toString()),
      "inspect the staged Rebased architecture",
      indicator,
    )
    if (requiredArchitecture !in architectures.stdout.split(Regex("\\s+"))) {
      throw RebasedMacUpdateException.Verification(
        "The staged application does not contain the required $requiredArchitecture architecture",
      )
    }
	    runVerificationCommand(
	      listOf(CODESIGN, "-dv", stagedApp.toString()),
	      "read the staged Rebased code signature",
	      indicator,
	    )
  }

  private fun plistValue(key: String, infoPlist: Path, indicator: ProgressIndicator): String {
    val result = runVerificationCommand(
      listOf(PLUTIL, "-extract", key, "raw", "-o", "-", infoPlist.toString()),
      "read $key from the staged Rebased application",
      indicator,
    )
    return result.stdout.trimEnd('\r', '\n')
  }

  private fun requireNoSymlinkComponents(vararg components: Path) {
    val symlink = components.firstOrNull(Files::isSymbolicLink) ?: return
    throw RebasedMacUpdateException.Verification("Required Rebased application path is symbolic: $symlink")
  }

  private fun requireRealDirectory(path: Path, container: Path): Path {
    if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw RebasedMacUpdateException.Verification("Required Rebased application directory is missing or symbolic: $path")
    }
    return try {
      val realContainer = container.toRealPath()
      val realPath = path.toRealPath()
      if (realPath == realContainer || !realPath.startsWith(realContainer)) {
        throw RebasedMacUpdateException.Verification("Rebased application directory escapes its container: $path")
      }
      realPath
    }
    catch (e: RebasedMacUpdateException.Verification) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("Cannot resolve Rebased application directory: $path", e)
    }
  }

  private fun requireRealFile(path: Path, realContainer: Path): Path {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw RebasedMacUpdateException.Verification("Required Rebased application file is missing or symbolic: $path")
    }
    return try {
      val realPath = path.toRealPath()
      if (!realPath.startsWith(realContainer)) {
        throw RebasedMacUpdateException.Verification("Rebased application file escapes the staged bundle: $path")
      }
      realPath
    }
    catch (e: RebasedMacUpdateException.Verification) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("Cannot resolve Rebased application file: $path", e)
    }
  }

  private fun run(
    arguments: List<String>,
    indicator: ProgressIndicator,
    timeoutMillis: Int,
  ): RebasedCommandResult =
    operations.run(arguments, indicator, timeoutMillis)

  private fun runPreparationCommand(
    arguments: List<String>,
    action: String,
    indicator: ProgressIndicator,
    timeoutMillis: Int,
    onResult: (RebasedCommandResult) -> Unit = {},
  ): RebasedCommandResult {
    val result = try {
      run(arguments, indicator, timeoutMillis)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Preparation("Failed to $action", e)
    }
    onResult(result)
    if (result.timedOut) {
      throw RebasedMacUpdateException.Preparation("Timed out while attempting to $action")
    }
    if (result.exitCode != 0) {
      throw RebasedMacUpdateException.Preparation(
        "Failed to $action (exit code ${result.exitCode}): ${result.stderr.trim()}",
      )
    }
    return result
  }

  private fun detachDmg(device: Path?, mountDirectory: Path) {
    var lastFailure: Throwable? = null
    val targets = if (device == null) listOf(mountDirectory) else listOf(device, mountDirectory)
    for (target in targets) {
      for (force in listOf(false, true)) {
        val arguments = buildList {
          add(HDIUTIL)
          add("detach")
          if (force) add("-force")
          add(target.toString())
        }
        try {
          runPreparationCommand(
            arguments = arguments,
            action = "detach the Rebased DMG",
            indicator = ProgressIndicatorBase(),
            timeoutMillis = SHORT_COMMAND_TIMEOUT_MILLIS,
          )
          return
        }
        catch (failure: Throwable) {
          lastFailure = failure
        }
      }
    }
    throw checkNotNull(lastFailure)
  }

  private fun cleanupUnattachedMountDirectory(mountDirectory: Path, primaryFailure: Throwable) {
    try {
      if (isEmptyPlainDirectory(mountDirectory)) {
        Files.deleteIfExists(mountDirectory)
      }
    }
    catch (cleanupFailure: Throwable) {
      primaryFailure.addSuppressed(cleanupFailure)
      LOG.warn("Failed to clean unattached Rebased update mount directory at $mountDirectory", cleanupFailure)
    }
  }

  private fun isEmptyPlainDirectory(directory: Path): Boolean {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return false
    return Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }
  }

  private fun runVerificationCommand(
    arguments: List<String>,
    action: String,
    indicator: ProgressIndicator,
  ): RebasedCommandResult {
    val result = try {
      run(arguments, indicator, SHORT_COMMAND_TIMEOUT_MILLIS)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw RebasedMacUpdateException.Verification("Failed to $action", e)
    }
    if (result.timedOut) {
      throw RebasedMacUpdateException.Verification("Timed out while attempting to $action")
    }
    if (result.exitCode != 0) {
      throw RebasedMacUpdateException.Verification(
        "Failed to $action (exit code ${result.exitCode}): ${result.stderr.trim()}",
      )
    }
    return result
  }

  private fun createMountDirectory(versionDirectory: Path): Path {
    val storeRoot = versionDirectory.parent
                    ?: throw IOException("Rebased update version directory has no parent: $versionDirectory")
    if (pathsOverlap(mountRoot, storeRoot)) {
      throw IOException("Rebased update mount root overlaps the update store: $mountRoot")
    }
    if (Files.exists(mountRoot, NOFOLLOW_LINKS) &&
        (!Files.isDirectory(mountRoot, NOFOLLOW_LINKS) || Files.isSymbolicLink(mountRoot))) {
      throw IOException("Refusing to use unsafe Rebased update mount root: $mountRoot")
    }
    Files.createDirectories(mountRoot)

    val realMountRoot = mountRoot.toRealPath()
    val realStoreRoot = storeRoot.toRealPath()
    if (pathsOverlap(realMountRoot, realStoreRoot)) {
      throw IOException("Rebased update mount root resolves inside the update store: $mountRoot")
    }
    return Files.createTempDirectory(realMountRoot, MOUNT_DIRECTORY_PREFIX)
  }

  private fun cleanup(versionDirectory: Path, failure: Throwable) {
    try {
      deleteVersionDirectory(versionDirectory)
    }
    catch (cleanupFailure: Throwable) {
      failure.addSuppressed(cleanupFailure)
      LOG.warn("Failed to clean incomplete Rebased update data in $versionDirectory", cleanupFailure)
    }
  }

  private fun deleteVersionDirectory(versionDirectory: Path) {
    val normalizedDirectory = versionDirectory.toAbsolutePath().normalize()
    val root = normalizedDirectory.parent
               ?: throw IOException("Rebased update version directory has no parent: $normalizedDirectory")
    if (Files.exists(root, NOFOLLOW_LINKS) &&
        (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root))) {
      throw IOException("Refusing to delete through unsafe Rebased update root: $root")
    }
    operations.deleteRecursively(normalizedDirectory)
  }
}

private fun ProgressIndicator.stage(text: String, indeterminate: Boolean = true) {
  checkCanceled()
  this.text = text
  isIndeterminate = indeterminate
}

private fun requireHttpsDmgUrl(value: String?): String {
  val url = value?.takeIf(::isAbsoluteHttpsUri)
            ?: throw RebasedMacUpdateException.Preparation(
              "The Rebased update has no valid HTTPS download URL",
            )
  val path = runCatching { URI(url).path }.getOrNull()
  if (path?.endsWith(".dmg", ignoreCase = true) != true) {
    throw RebasedMacUpdateException.Preparation("The Rebased update download URL does not identify a DMG")
  }
  return url
}

private fun attachCommand(mountDirectory: Path, dmg: Path): List<String> =
  listOf(
    HDIUTIL, "attach", "-readonly", "-noautoopen", "-noautofsck", "-nobrowse",
    "-plist", "-mountpoint", mountDirectory.toString(), dmg.toString(),
  )

private fun parseAttachDevice(output: String): Path? {
  val plist = try {
    JDOMUtil.load(output)
  }
  catch (_: Exception) {
    return null
  }
  if (plist.name != "plist") return null
  val dictionary = plist.getChild("dict") ?: return null
  val entities = plistValue(dictionary, "system-entities")?.takeIf { it.name == "array" } ?: return null
  return entities.children.asSequence()
    .filter { it.name == "dict" }
    .mapNotNull { plistValue(it, "dev-entry") }
    .filter { it.name == "string" }
    .mapNotNull { validDevicePath(it.text) }
    .firstOrNull()
}

private fun plistValue(dictionary: Element, key: String): Element? {
  val children = dictionary.children
  for (index in 0 until children.lastIndex) {
    if (children[index].name == "key" && children[index].text == key) {
      return children[index + 1]
    }
  }
  return null
}

private fun validDevicePath(value: String): Path? {
  val path = try {
    Path.of(value)
  }
  catch (_: Exception) {
    return null
  }
  if (!path.isAbsolute || path.normalize() != path || path.parent != DEVICE_DIRECTORY) return null
  val name = path.fileName.toString()
  if (!name.startsWith("disk")) return null
  val suffix = name.removePrefix("disk")
  if (!suffix.split('s').all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) return null
  return path
}

private fun pathsOverlap(first: Path, second: Path): Boolean =
  first == second || first.startsWith(second) || second.startsWith(first)

private val DEVICE_DIRECTORY = Path.of("/dev")
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
private const val APP_NAME = "Rebased.app"
private const val PART_DMG_NAME = "rebased.dmg.part"
private const val VERIFIED_DMG_NAME = "rebased.dmg"
private const val DEFAULT_MOUNT_ROOT_NAME = "rebased-update-mounts"
private const val MOUNT_DIRECTORY_PREFIX = "rebased-"
private const val BUNDLE_ID = "io.github.detachhead.rebased"
private const val BUNDLE_EXECUTABLE = "rebased"
private const val HDIUTIL = "/usr/bin/hdiutil"
private const val DITTO = "/usr/bin/ditto"
private const val PLUTIL = "/usr/bin/plutil"
private const val LIPO = "/usr/bin/lipo"
private const val CODESIGN = "/usr/bin/codesign"
private const val CASK_NAME = "rebased"
private const val SHORT_COMMAND_TIMEOUT_MILLIS = 60_000
private const val DITTO_TIMEOUT_MILLIS = 10 * 60_000
private const val HOMEBREW_LONG_TIMEOUT_MILLIS = 10 * 60_000
private const val MAX_COMMAND_DIAGNOSTICS_LENGTH = 2_048
private const val MAX_STDERR_COMMAND_OUTPUT_LENGTH = MAX_COMMAND_DIAGNOSTICS_LENGTH * 3 / 4
private const val MAX_STDOUT_COMMAND_OUTPUT_LENGTH = MAX_COMMAND_DIAGNOSTICS_LENGTH - MAX_STDERR_COMMAND_OUTPUT_LENGTH
private val LOG = logger<RebasedMacUpdatePreparer>()
