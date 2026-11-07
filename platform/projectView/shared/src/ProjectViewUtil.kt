@file:ApiStatus.Internal
// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.projectView

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.isControlFlowException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
suspend inline fun runSafelyCancellable(logger: Logger, taskDescription: () -> String, block: suspend () -> Unit) {
  try {
    block()
  }
  catch (e: Throwable) {
    if (e.isControlFlowException) {
      currentCoroutineContext().ensureActive()
      logger.warn("Got a CFE without cancellation, task: ${taskDescription()}", RuntimeException(e))
    }
    else {
      logger.error("A safe cancellable task crashed: ${taskDescription()}", e)
    }
  }
}
