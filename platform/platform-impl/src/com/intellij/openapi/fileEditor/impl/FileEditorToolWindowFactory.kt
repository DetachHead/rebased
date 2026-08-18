// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

internal class FileEditorToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun init(window: ToolWindow) {
    EditorHostCoordinator.getInstance(window.project).bindVisibility(window)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl ?: return
    EditorHostCoordinator.getInstance(project).install(toolWindow, manager)
  }
}
