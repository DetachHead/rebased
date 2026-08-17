// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.vcs.log.VcsLogBundle
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon
import javax.swing.JComponent

@ApiStatus.Internal
class VcsLogFileType private constructor() : FileType {
  override fun getName(): String = "VcsLog"
  override fun getDescription(): String = VcsLogBundle.message("filetype.vcs.log.description")
  override fun getDefaultExtension(): String = ""
  override fun getIcon(): Icon = AllIcons.Vcs.Branch
  override fun isBinary(): Boolean = true
  override fun isReadOnly(): Boolean = true

  companion object {
    val INSTANCE = VcsLogFileType()
  }
}

abstract class VcsLogFile(name: String) : LightVirtualFile(name, VcsLogFileType.INSTANCE, "") {
  init {
    isWritable = false
  }

  abstract fun createMainComponent(project: Project): JComponent
}
