package com.intellij.vcs.log.ui;

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.vcs.log.ui.editor.DefaultVcsLogFile


class CanCloseVirtualLogFile: VirtualFilePreCloseCheck {
  /**
   * don't allow the first vcs log window to be closed
   */
  override fun canCloseFile(file: VirtualFile): Boolean {
    return file !is DefaultVcsLogFile || !file.isFirstLogFile
  }
}
