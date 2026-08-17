// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.impl.ContentImpl

internal class FileEditorToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun init(window: ToolWindow) {
    val project = window.project
    val connection = project.messageBus.connect(window.disposable)
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!window.isVisible) {
          window.show(null)
        }
      }

      override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (!source.hasOpenFiles() && window.isVisible) {
          window.hide(null)
        }
      }
    })
    ToolWindowManager.getInstance(project).invokeLater {
      if (FileEditorManager.getInstance(project).hasOpenFiles() && !window.isVisible) {
        window.show(null)
      }
    }
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl ?: return
    (ToolWindowManager.getInstance(project) as? ToolWindowManagerImpl)?.detachDocumentComponent(manager.mainSplitters)
    val content = ContentFactory.getInstance().createContent(manager.mainSplitters, null, false).apply {
      isCloseable = false
      (this as ContentImpl).setShouldDisposeContent(false)
    }
    toolWindow.contentManager.addContent(content)
  }
}
