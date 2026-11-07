// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.ui.breakpoints

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
fun interface BreakpointThreadDumpCapture {
  /**
   * Captures a thread dump and reports the result.
   */
  suspend fun capture(context: SuspendContextImpl, event: BreakpointThreadDumpEvent)

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<BreakpointThreadDumpCapture> =
      ExtensionPointName.create("com.intellij.debugger.breakpointThreadDumpCapture")

    /** Whether at least one capture is registered. A breakpoint without a capture skips the dump. */
    @JvmStatic
    fun isAvailable(): Boolean = EP_NAME.hasAnyExtensions()
  }
}

/** Identifies a breakpoint hit. The file URL and zero-based line stay fixed after the hit. */
@ApiStatus.Internal
data class BreakpointThreadDumpEvent(
  val breakpoint: XBreakpoint<*>,
  val session: XDebugSession,
  val captureStartedAt: Long,
  val fileUrl: String?,
  val line: Int?,
)
