// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.collaboration.ui.util.popup

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.annotations.ApiStatus
import kotlin.coroutines.resume

suspend fun JBPopup.awaitClose() {
  checkDisposed()
  return try {
    suspendCancellableCoroutine { continuation ->
      continueWhenPopupClosed(continuation) { }
    }
  }
  catch (e: CancellationException) {
    cancel()
    throw e
  }
}

@ApiStatus.Internal
fun <T> JBPopup.continueWhenPopupClosed(cont: CancellableContinuation<T>, chosenValue: () -> T) {
  val listener = object : JBPopupListener {
    override fun onClosed(event: LightweightWindowEvent) {
      when {
        event.isOk -> cont.resume(chosenValue())
        else -> cont.cancel()
      }
    }
  }
  addListener(listener)
}

@ApiStatus.Internal
@Throws(CancellationException::class)
suspend fun JBPopup.checkDisposed() {
  if (isDisposed) {
    val ctx = currentCoroutineContext()
    ctx.cancel()
    ctx.ensureActive()
  }
}
