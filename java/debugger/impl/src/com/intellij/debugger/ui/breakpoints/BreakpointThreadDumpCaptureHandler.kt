// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.ui.breakpoints

import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
internal class BreakpointThreadDumpCaptureHandler(private val scope: CoroutineScope) {
  companion object {
    @JvmStatic
    fun getInstance(project: Project): BreakpointThreadDumpCaptureHandler = project.service()
  }

  fun captureAsyncAndNotify(breakpoint: Breakpoint<*>, context: SuspendContextImpl) {
    DebuggerManagerThreadImpl.assertIsManagerThread()
    val session = context.debugProcess.xdebugProcess?.session ?: return
    val xBreakpoint = breakpoint.xBreakpoint as? XBreakpointBase<*, *, *> ?: return
    val policy = xBreakpoint.threadDumpCapturePolicy ?: return
    if (!xBreakpoint.threadDumpCaptureState.tryCapture(policy, session)) return
    val lineBreakpoint = xBreakpoint as? XLineBreakpoint<*>
    val event = BreakpointThreadDumpEvent(
      xBreakpoint, session, System.currentTimeMillis(), lineBreakpoint?.fileUrl, lineBreakpoint?.line,
    )
    BreakpointThreadDumpCapture.EP_NAME.forEachExtensionSafe { captureExtension ->
      scope.launch {
        captureExtension.capture(context, event)
      }
    }
  }
}
