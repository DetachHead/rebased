// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.breakpoints

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/**
 * Defines when a breakpoint saves a thread dump on a hit.
 *
 * The policy is a part of the persistent breakpoint state, see [XBreakpointBase.setThreadDumpCapturePolicy].
 * The hits already taken in a debug session are tracked by [ThreadDumpCaptureState], which is transient.
 */
@ApiStatus.Internal
sealed interface ThreadDumpCapturePolicy {
  /** Saves a dump at the first hit in each debug session. Later hits in the same session are skipped. */
  object OncePerSession : ThreadDumpCapturePolicy {
    override fun toString(): String = "OncePerSession"
  }

  companion object {
    private const val ONCE_PER_SESSION = "ONCE_PER_SESSION"

    /** Returns null for a missing or unknown persisted state, so that a newer IDE state disables the capture. */
    @JvmStatic
    fun fromState(state: BreakpointState.ThreadDumpCapture?): ThreadDumpCapturePolicy? = when (state?.policy) {
      ONCE_PER_SESSION -> OncePerSession
      else -> null
    }

    @JvmStatic
    fun toState(policy: ThreadDumpCapturePolicy?): BreakpointState.ThreadDumpCapture? = when (policy) {
      null -> null
      OncePerSession -> BreakpointState.ThreadDumpCapture(ONCE_PER_SESSION)
    }
  }
}

/**
 * Counts the thread dumps that a breakpoint has taken in each running debug session.
 *
 * A session entry is removed when the session stops. [reset] drops every entry, for example after a policy change.
 */
@ApiStatus.Internal
class ThreadDumpCaptureState {
  private val capturesPerSession = HashMap<XDebugSession, Int>()

  /**
   * Records a hit and returns true when [policy] allows a dump for it.
   * A stopped session is not recorded.
   */
  @RequiresBackgroundThread
  fun tryCapture(policy: ThreadDumpCapturePolicy, session: XDebugSession): Boolean {
    val allowed = synchronized(this) {
      val captures = capturesPerSession[session]
      if (captures == null) {
        capturesPerSession[session] = 0
        session.addSessionListener(object : XDebugSessionListener {
          override fun sessionStopped() {
            sessionStopped(session)
          }
        })
      }
      val taken = captures ?: 0
      val allowed = when (policy) {
        ThreadDumpCapturePolicy.OncePerSession -> taken == 0
      }
      if (allowed) {
        capturesPerSession[session] = taken + 1
      }
      allowed
    }
    // The session can stop between the hit and the listener registration. Then the listener never fires.
    if (session.isStopped) {
      sessionStopped(session)
    }
    return allowed
  }

  fun reset() {
    synchronized(this) {
      capturesPerSession.clear()
    }
  }

  @get:TestOnly
  val isEmpty: Boolean
    @RequiresBackgroundThread get() = synchronized(this) { capturesPerSession.isEmpty() }

  @RequiresBackgroundThread
  private fun sessionStopped(session: XDebugSession) {
    synchronized(this) {
      capturesPerSession.remove(session)
    }
  }
}
