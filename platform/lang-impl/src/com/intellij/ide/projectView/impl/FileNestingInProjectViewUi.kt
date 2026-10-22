// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.projectView.impl

import com.intellij.ide.IdeBundle
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected

internal class FileNestingInProjectViewUi(toolbarDecorator: ToolbarDecorator) {

  lateinit var useNestingRulesCheckBox: JBCheckBox

  @JvmField
  val panel = panel {
    row {
      useNestingRulesCheckBox = checkBox(IdeBundle.message("file.nesting.feature.enabled.checkbox"))
        .component
    }

    row {
      cell(toolbarDecorator.createPanel())
        .label(IdeBundle.message("file.nesting.table.title"), LabelPosition.TOP)
        .align(Align.FILL)
    }.resizableRow()
      .enabledIf(useNestingRulesCheckBox.selected)
  }
}
