// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen.rightTab

import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.wm.ex.WelcomeScreenTabService
import com.intellij.platform.ide.nonModalWelcomeScreen.NonModalWelcomeScreenBundle
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

/**
 * The backing file of the welcome tab.
 *
 * The file is the identity of the tab, and nothing more: the platform persists it in the editor state of the
 * project and resolves it again by URL through [WelcomeScreenRightTabFileSystem]. The editor the platform creates
 * for the file owns the tab UI. There is one instance per content provider, handed out by the file system, so a
 * repeated open focuses the open tab instead of adding a second one.
 */
@ApiStatus.Internal
class WelcomeScreenRightTabVirtualFile internal constructor(
  private val fileSystem: WelcomeScreenRightTabFileSystem,
  contentProvider: WelcomeRightTabContentProvider,
) : LightVirtualFile(contentProvider.title.get(), WelcomeScreenFileType(contentProvider), ""),
    EditorHistoryManager.OptionallyIncluded {

  init {
    putUserData(WelcomeScreenTabService.WELCOME_TAB_FILE_MARKER, true)
    putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    isWritable = false
  }

  override fun getFileSystem(): VirtualFileSystem = fileSystem

  override fun getPath(): String = WELCOME_SCREEN_TAB_PATH

  override fun shouldSkipEventSystem(): Boolean = true

  /** The tab is a screen, not a document the user worked in, so Recent Files does not list it. */
  override fun isIncludedInEditorHistory(project: Project): Boolean = false

  companion object {
    /** The welcome tab file, or `null` when the product installs no [WelcomeRightTabContentProvider]. */
    fun getInstance(): WelcomeScreenRightTabVirtualFile? = welcomeScreenRightTabFileSystem().getFile()
  }

  @ApiStatus.Internal
  class WelcomeScreenFileType(
    private val contentProvider: WelcomeRightTabContentProvider
  ) : FakeFileType() {
    override fun getName(): @NonNls String = contentProvider.title.get()

    override fun getDescription(): @NlsContexts.Label String =
      NonModalWelcomeScreenBundle.message("welcome.screen.virtual.file.type.description")

    override fun getIcon(): Icon = contentProvider.fileTypeIcon

    override fun isMyFileType(file: VirtualFile): Boolean {
      return file is WelcomeScreenRightTabVirtualFile
    }
  }
}
