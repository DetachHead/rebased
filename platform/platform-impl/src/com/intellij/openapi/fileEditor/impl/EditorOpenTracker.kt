// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.coroutines.AsyncTaskTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.TestOnly
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Tracks editor opens in a project so callers can await completion.
 *
 * Kinds of operations are tracked:
 * - an [EditorComposite] which is created but has not published its editors yet (see [trackComposite]);
 *   this covers every open path, because every composite is created at a single point;
 * - an open request started through [launchTracked]; this covers the work which happens after the
 *   composite is available: caret positioning and open callbacks.
 * - suspending opens until they return or throw ([runWithTracking]).
 */
@Internal
@Service(Service.Level.PROJECT)
class EditorOpenTracker(private val coroutineScope: CoroutineScope) : AsyncTaskTracker() {

  /**
   * Tracks [action] until it returns or throws, without tracking the caller's remaining work.
   */
  public override suspend fun <T> runWithTracking(action: suspend () -> T): T = super.runWithTracking(action)

  /**
   * Starts [block] as a tracked editor open.
   * Pass [scope] to bind it to a lifetime shorter than the project's, such as a composite's.
   */
  fun launchTracked(
    context: CoroutineContext = EmptyCoroutineContext,
    scope: CoroutineScope = coroutineScope,
    block: suspend CoroutineScope.() -> Unit,
  ): Job {
    return createTracked(scope, context, shouldStartImmediately = true, block)
  }

  /**
   * Tracks [composite] until it publishes its editors or closes, without starting a coroutine.
   * Initialization starts when its panel is first shown, or immediately in headless mode.
   */
  fun trackComposite(composite: EditorComposite) {
    register(composite.availableJob)
  }

  /**
   * Use [launchTracked] in production
   */
  @TestOnly
  fun track(job: Job) {
    register(job)
  }

  /**
   * Builds a snapshot barrier: a job which completes when all editor opens pending at this call have completed.
   * Cancellation of a pending open counts as completion.
   * Failures of pending opens are not propagated through it.
   */
  fun pendingEditorOpen(): Job = pending()

  companion object {
    @JvmStatic
    fun getInstance(project: Project): EditorOpenTracker = project.service()
  }
}
