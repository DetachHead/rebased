// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.util.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * General purpose coroutine tasks tracker.
 *
 * Main functionality consists of two edges:
 * - launch coroutine task, returning a completion handle
 * - provide common completion [Job] barrier for all currently pending tasks
 *
 * A task is registered before it starts, so a barrier taken on another thread right after the submit
 * observes the task. Keep that order for a task which the tracker does not start itself: register it
 * through [register] while it is still lazy, or wrap it with [runWithTracking]
 */
@Internal
open class AsyncTaskTracker {
  private val pendingTasks: MutableSet<Job> = ConcurrentHashMap.newKeySet()

  /**
   * Creates a lazy task in [scope] and registers it.
   * By default, a caller starts the returned job whenever needed.
   * Set [shouldStartImmediately] == 'true' to launch immediately after registration.
   */
  protected fun createTracked(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    shouldStartImmediately: Boolean = false,
    block: suspend CoroutineScope.() -> Unit,
  ): Job {
    val job = scope.launch(context, start = CoroutineStart.LAZY, block = block)
    register(job)
    if (shouldStartImmediately) {
      job.start()
    }
    return job
  }

  /**
   * Runs [action] while exposing its execution as a pending task.
   * A separate token is tracked instead of the caller's job, so completion of [action] completes the entry.
   */
  protected open suspend fun <T> runWithTracking(action: suspend () -> T): T {
    val task = Job()
    register(task)
    return try {
      action()
    }
    finally {
      task.complete()
    }
  }

  /**
   * Registers [job] as a pending task. Call it before [job] starts.
   */
  protected fun register(job: Job) {
    pendingTasks.add(job)
    job.invokeOnCompletion {
      pendingTasks.remove(job)
    }
  }

  /**
   * Builds a snapshot barrier: a job which completes when all tasks pending at this call have completed.
   * A task submitted later is not included. Cancellation of a task counts as completion,
   * and a failure of a task is not propagated through the barrier.
   */
  fun pending(): Job {
    val tasks = pendingTasks.toList()
    val result = Job()
    if (tasks.isEmpty()) {
      result.complete()
      return result
    }

    val remainingTasks = AtomicInteger(tasks.size)
    for (task in tasks) {
      task.invokeOnCompletion {
        if (remainingTasks.decrementAndGet() == 0) {
          result.complete()
        }
      }
    }
    return result
  }
}
