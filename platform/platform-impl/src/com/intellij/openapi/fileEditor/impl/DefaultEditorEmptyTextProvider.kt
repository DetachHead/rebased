// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.ide.IdeBundle
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.JComponent

internal class DefaultEditorEmptyTextProvider : EditorEmptyTextProvider {
  override fun appendEmptyText(splitters: JComponent, sink: EditorEmptyTextSink) {
    sink.appendActionWithFirstKeyboardShortcut("Open Git Log", "Vcs.Log.OpenAnotherTabInEditor")
    sink.appendToolWindow(IdeBundle.message("empty.text.commit.view"), ToolWindowId.COMMIT)
    sink.appendToolWindow(IdeBundle.message("empty.text.terminal.view"), ToolWindowId.TERMINAL)
    sink.appendToolWindow(IdeBundle.message("empty.text.pr.view"), ToolWindowId.PULL_REQUESTS)
  }
}
