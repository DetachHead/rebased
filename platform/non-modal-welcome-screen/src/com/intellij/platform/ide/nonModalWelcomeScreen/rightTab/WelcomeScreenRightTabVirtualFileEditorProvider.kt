// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen.rightTab

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.nonModalWelcomeScreen.WelcomeScreenTabUsageCollector
import org.jetbrains.annotations.NonNls

/**
 * Creates the welcome tab editor from the file alone, which is what a restore from the editor state needs.
 * A tab the user closed leaves the editor state, so the platform does not bring it back.
 */
internal class WelcomeScreenRightTabVirtualFileEditorProvider : FileEditorProvider, DumbAware {
  companion object {
    const val ID: String = "NewProjectWindowFileEditor"
  }

  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file is WelcomeScreenRightTabVirtualFile &&
           WelcomeScreenRightTab.isRightTabEnabled &&
           WelcomeRightTabContentProvider.getSingleExtension() != null
  }

  override fun acceptRequiresReadAction(): Boolean = false

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    val contentProvider = checkNotNull(WelcomeRightTabContentProvider.getSingleExtension()) {
      "The welcome tab content provider disappeared after accept()"
    }
    val editor = WelcomeScreenRightTabVirtualFileEditor(project, file as WelcomeScreenRightTabVirtualFile, contentProvider)
    // a restored tab counts as an opened tab, so the counter stays comparable across the restore path
    WelcomeScreenTabUsageCollector.logWelcomeScreenTabOpened()
    return editor
  }

  override fun getEditorTypeId(): @NonNls String = ID

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

  override fun isDumbAware(): Boolean = true
}
