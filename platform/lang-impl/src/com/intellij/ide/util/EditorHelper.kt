// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("DEPRECATION")

package com.intellij.ide.util

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.currentClassLogger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.EditorOpenFuture
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorOpenTracker
import com.intellij.openapi.fileEditor.impl.selectPreferredTextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.concurrent.CompletableFuture

private val LOG = currentClassLogger()

/**
 * Opens an editor for [element] without blocking the EDT while the editor composite is being created.
 * NB: the returned text editor may still be loading;
 * use [FileEditorManager.runWhenLoaded] before accessing its document UI.
 */
@Internal
suspend fun openInEditor(
  element: PsiElement,
  switchToText: Boolean = true,
  requestFocus: Boolean = false,
): FileEditor? {
  val descriptor = readAction {
    createOpenFileDescriptor(element)
  }
  if (descriptor == null) {
    LOG.debug { "Skipped opening an editor: the element is invalid or has no virtual file (${element.javaClass.name})" }
    return null
  }
  return openInEditor(descriptor, switchToText = switchToText, requestFocus = requestFocus)
}

private suspend fun openInEditor(
  descriptor: OpenFileDescriptor,
  switchToText: Boolean,
  requestFocus: Boolean,
): FileEditor? {
  val fileEditorManager = descriptor.project.serviceAsync<FileEditorManager>()
  val composite = fileEditorManager.openEditor(descriptor, FileEditorOpenRequest.fromDescriptor(descriptor).withRequestFocus(requestFocus))
  val keepSelectedEditor = !switchToText && readAction { descriptor.offset == -1 }
  return withContext(Dispatchers.UI) {
    val selectedEditor = fileEditorManager.getSelectedEditor(descriptor.file)
    if (keepSelectedEditor) {
      selectedEditor?.takeIf { it in composite.allEditors } ?: composite.allEditors.firstOrNull()
    }
    else {
      selectPreferredTextEditor(composite, selectedEditor)
    }
  }
}

/**
 * Suspend-compatible bridge
 * Opens the files in order. Skips elements without a navigation target.
 * @return [CompletableFuture] completing when all open requests have been processed
 * Canceling the future stops the remaining requests but leaves created tabs open.
 * @see [EditorHelper]
 */
@Internal
@RequiresReadLock
fun requestOpenFilesInEditor(elements: Array<out PsiElement>): CompletableFuture<Void?> {
  val descriptors = elements.mapNotNull(::createOpenFileDescriptor)
  val project = descriptors.firstOrNull()?.project ?: return EditorOpenFuture.completed(null)
  return project.launchOpenRequest {
    for (descriptor in descriptors) {
      openInEditor(descriptor, switchToText = true, requestFocus = true)
    }
    null
  }
}

/**
 * Opens the text editor asynchronously. The future contains `null` if no text editor opens.
 * Successful completion runs on EDT with the submitting modality. Callbacks can start read or write actions.
 * Callbacks added after completion run on the calling thread.
 * Returns a completed future if no target exists.
 * @see [EditorHelper]
 */
@Internal
@RequiresReadLock
fun requestOpenInEditor(element: PsiElement, requestFocus: Boolean): CompletableFuture<Editor?> {
  val descriptor = createOpenFileDescriptor(element) ?: return EditorOpenFuture.completed(null)
  return descriptor.project.launchOpenRequest {
    val fileEditor = openInEditor(descriptor, switchToText = true, requestFocus = requestFocus)
    withContext(Dispatchers.UI) {
      (fileEditor as? TextEditor)?.editor
    }
  }
}

/**
 * Same as [requestOpenInEditor], but returns the selected [FileEditor].
 * With [switchToText] set to `false`, this can return an image viewer or another non-text editor.
 * The future contains `null` if no suitable editor opens.
 */
@Internal
@RequiresReadLock
fun requestOpenInEditor(
  element: PsiElement,
  switchToText: Boolean,
  requestFocus: Boolean,
): CompletableFuture<FileEditor?> {
  val descriptor = createOpenFileDescriptor(element)
  if (descriptor == null) {
    LOG.debug { "Skipped opening an editor: the element is invalid or has no virtual file (${element.javaClass.name})" }
    return EditorOpenFuture.completed(null)
  }
  return descriptor.project.launchOpenRequest {
    openInEditor(descriptor, requestFocus = requestFocus, switchToText = switchToText)
  }
}

private inline fun <T> Project.launchOpenRequest(crossinline action: suspend () -> T): CompletableFuture<T> {
  // the tracker owns a project scope and registers the job before it starts,
  // so a barrier taken on another thread right after the submit observes it
  val context = Dispatchers.Default + ClientId.coroutineContext() + ModalityState.defaultModalityState().asContextElement()
  val future = EditorOpenFuture<T>()
  val job = EditorOpenTracker.getInstance(this).launchTracked(context) {
    runCatching {
      val result = action()
      withContext(Dispatchers.UiWithModelAccess) {
        future.complete(result)
      }
    }.onFailure { future.completeExceptionally(it) }.getOrLogException(LOG)
  }
  job.invokeOnCompletion { cause ->
    if (cause != null) future.completeExceptionally(cause)
  }
  future.whenComplete { _, _ ->
    if (future.isCancelled) job.cancel()
  }
  return future
}

@RequiresReadLock
private fun createOpenFileDescriptor(element: PsiElement): OpenFileDescriptor? {
  if (!element.isValid) {
    return null
  }
  val file = element as? PsiFile ?: (element.containingFile ?: return null)
  if (!file.isValid) {
    return null
  }
  val offset = if (element is PsiFile) -1 else element.textOffset
  val virtualFile = file.virtualFile ?: return null
  return OpenFileDescriptor(element.project, virtualFile, offset)
}
