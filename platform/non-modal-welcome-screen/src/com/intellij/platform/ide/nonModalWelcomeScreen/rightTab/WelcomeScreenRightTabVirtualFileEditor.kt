// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen.rightTab

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.nonModalWelcomeScreen.NonModalWelcomeScreenBundle
import org.jetbrains.annotations.Nls
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * The editor of the welcome tab. It owns the tab UI: it builds [WelcomeScreenRightTabImpl] and disposes it with
 * itself, so a tab the platform restores from the editor state gets its UI the same way an opened tab does.
 */
internal class WelcomeScreenRightTabVirtualFileEditor(
  project: Project,
  private val file: WelcomeScreenRightTabVirtualFile,
  contentProvider: WelcomeRightTabContentProvider,
) : FileEditor {
  private val userDataHolder: UserDataHolder = UserDataHolderBase()

  private val tab: WelcomeScreenRightTab = WelcomeScreenRightTabImpl(project, contentProvider)

  init {
    userDataHolder.putUserData(FileEditorManagerKeys.DUMB_AWARE, true)
    userDataHolder.putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
    userDataHolder.putUserData(FileEditorManagerKeys.SINGLETON_EDITOR_IN_WINDOW, true)
  }

  override fun getFile(): VirtualFile = file

  override fun getComponent(): JComponent = tab.component

  override fun getPreferredFocusedComponent(): JComponent = tab.getPreferredFocusedComponent()

  override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String =
    NonModalWelcomeScreenBundle.message("welcome.screen.editor.name")

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    Disposer.dispose(tab)
  }

  override fun <T> getUserData(key: Key<T?>): T? {
    return userDataHolder.getUserData(key)
  }

  override fun <T> putUserData(key: Key<T?>, value: T?) {
    userDataHolder.putUserData(key, value)
  }
}
