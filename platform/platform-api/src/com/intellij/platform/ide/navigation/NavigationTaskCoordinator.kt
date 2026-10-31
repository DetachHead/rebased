// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.navigation

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.util.coroutines.AsyncTaskTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext

private val shouldRescheduleNavigationFromWriteLock: Boolean
  get() = Registry.`is`("ide.navigation.write.lock.free")

/**
 * Tracks navigation tasks of a project so that tests can explicitly await their completion
 * instead of relying on `isUnitTestMode` in the production navigation path.
 *
 * Tasks dispatched by [requestNavigate] are registered before they start.
 * Direct [NavigationService] calls are tracked from the moment they enter the service implementation.
 */
@ApiStatus.Internal
@Service(Service.Level.PROJECT)
class NavigationTaskCoordinator(
  private val navigationScope: CoroutineScope,
) : AsyncTaskTracker() {

  /**
   * Runs [action] while exposing its execution as pending navigation.
   * A separate token is tracked instead of the caller's job, so completion of [action] completes the tracking entry
   */
  public override suspend fun <T> runWithTracking(action: suspend () -> T): T = super.runWithTracking(action)

  /**
   * If the UI context is not ready, captures the caller's modality and client until [action] obtains it on the EDT.
   * Cancelling the future cancels the task. Task failure or cancellation completes the future exceptionally.
   *
   * Migration step: if [shouldRescheduleNavigationFromWriteLock] == 'true', the task is rescheduled
   * after the Write Action. In cases otherwise, for callsites using WA, an error would be logged.
   */
  internal fun dispatchNavigation(
    coroutineScope: CoroutineScope? = null,
    navigateContext: NavigationTaskContext? = null,
    action: suspend () -> Boolean,
  ): CompletableFuture<Boolean> {
    val scope = coroutineScope.orServiceScope()
    val modalityState = navigateContext?.modalityState ?: ModalityState.defaultModalityState()
    val context = navigateContext?.coroutineContext ?: (ClientId.coroutineContext() + modalityState.asContextElement())
    val task = createTask(scope, context, action)
    val taskFuture = task.asCompletableFuture()
    startNavigationTask(task, modalityState)
    return taskFuture
  }

  /**
   * Creates a lazy navigation task and atomically registers it,
   * so the task is visible to [pendingNavigation] before the caller starts it.
   */
  private fun createTask(scope: CoroutineScope, context: CoroutineContext, action: suspend () -> Boolean): Deferred<Boolean> {
    val task = scope.async(context, start = CoroutineStart.LAZY) {
      action()
    }
    register(task)
    return task
  }

  /**
   * Builds a snapshot barrier: a job which completes when all navigation tasks pending at this call have completed.
   * Tasks submitted later are not included. Cancellation of a navigation task counts as completion.
   * Failures of navigation tasks are not propagated through it.
   */
  internal fun pendingNavigation(): Job = pending()

  private fun CoroutineScope?.orServiceScope(): CoroutineScope = this ?: navigationScope

  private fun startNavigationTask(task: Job, modalityState: ModalityState) {
    val application = ApplicationManager.getApplication()
    if (application.isWriteAccessAllowed) {
      if (shouldRescheduleNavigationFromWriteLock) {
        application.invokeLater({ task.start() }, modalityState)
        return
      }
      else {
        reportNavigationUnderWriteAction()
      }
    }
    task.start()
  }

  private fun reportNavigationUnderWriteAction() {
    LOG.error(Throwable(
      """
        Navigation must not be submitted under the Write Action.
        Move the call site out of the Write Action OR
        set 'ide.navigation.write.lock.free=true' to make it rescheduled.
      """.trimIndent()
    ))
  }

  companion object {
    private val LOG = thisLogger()

    @JvmStatic
    fun getInstance(project: Project): NavigationTaskCoordinator = project.service()
  }
}
