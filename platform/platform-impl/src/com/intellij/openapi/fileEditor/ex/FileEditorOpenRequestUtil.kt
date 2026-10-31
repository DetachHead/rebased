// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.ex

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorOpenMode
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.fileEditor.impl.getOpenMode
import com.intellij.util.ui.EDT
import org.jetbrains.annotations.ApiStatus

internal fun resolveOpenMode(request: FileEditorOpenRequest): FileEditorOpenRequest {
  if (request.openMode != FileEditorOpenMode.MANAGED) {
    return request
  }
  if (!EDT.isCurrentThreadEdt()) {
    return request.withOpenMode(FileEditorOpenMode.DEFAULT)
  }
  // Please let's prefer explicitness to inference
  val mode = when (getOpenMode(IdeEventQueue.getInstance().trueCurrentEvent)) {
    FileEditorManagerImpl.OpenMode.DEFAULT -> FileEditorOpenMode.DEFAULT
    FileEditorManagerImpl.OpenMode.RIGHT_SPLIT -> FileEditorOpenMode.RIGHT_SPLIT
    FileEditorManagerImpl.OpenMode.NEW_WINDOW -> FileEditorOpenMode.NEW_WINDOW
  }
  return request.withOpenMode(mode)
}

@ApiStatus.Internal
fun buildFileEditorOpenOptions(request: FileEditorOpenRequest, waitForCompositeOpen: Boolean = true): FileEditorOpenOptions =
  FileEditorOpenOptions(
    selectAsCurrent = request.selectAsCurrent,
    reuseOpen = request.reuseOpen,
    usePreviewTab = request.usePreviewTab,
    requestFocus = request.requestFocus,
    pin = request.pin,
    openMode = when (request.openMode) {
      FileEditorOpenMode.MANAGED, FileEditorOpenMode.DEFAULT -> FileEditorManagerImpl.OpenMode.DEFAULT
      FileEditorOpenMode.RIGHT_SPLIT -> FileEditorManagerImpl.OpenMode.RIGHT_SPLIT
      FileEditorOpenMode.NEW_WINDOW -> FileEditorManagerImpl.OpenMode.NEW_WINDOW
    },
    waitForCompositeOpen = waitForCompositeOpen,
  )
