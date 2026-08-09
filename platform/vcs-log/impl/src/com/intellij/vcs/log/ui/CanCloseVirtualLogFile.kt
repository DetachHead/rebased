package com.intellij.vcs.log.ui;

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.vcs.log.impl.CommonUiProperties
import com.intellij.vcs.log.impl.VcsLogApplicationSettings
import com.intellij.vcs.log.ui.editor.DefaultVcsLogFile


class CanCloseVirtualLogFile: VirtualFilePreCloseCheck {
  /**
   * don't allow the first vcs log window to be closed (unless show in editor is disabled, in which case it should behave like it does in
   * jetbrains IDEs)
   */
  override fun canCloseFile(file: VirtualFile): Boolean {
    return file !is DefaultVcsLogFile || !file.isFirstLogFile || !service<VcsLogApplicationSettings>()[CommonUiProperties.SHOW_IN_EDITOR]
  }
}
