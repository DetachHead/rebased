// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.zuban.frontend

import com.intellij.python.lsp.core.frontend.LspPyToolFrontend
import com.intellij.python.pytools.common.FusId
import com.intellij.python.zuban.common.ZubanTypeCheckingMode
import com.intellij.python.zuban.common.icons.PythonZubanCommonIcons
import com.intellij.util.IconUtil
import javax.swing.Icon

internal class ZubanPyToolFrontend : LspPyToolFrontend {
  override val presentableName: String = "Zuban"
  override val description: String get() = ZubanFrontendBundle.message("zuban.tool.description")
  override val fusId: FusId = FusId("zuban")
  override val icon: Icon = IconUtil.resizeSquared(PythonZubanCommonIcons.Zuban, 16)

  override val typeCheckingModes: Map<String, String>
    get() = ZubanTypeCheckingMode.entries.associate { mode ->
      mode.value to when (mode) {
        ZubanTypeCheckingMode.AUTO -> ZubanFrontendBundle.message("zuban.type.checking.mode.auto")
        ZubanTypeCheckingMode.DEFAULT -> ZubanFrontendBundle.message("zuban.type.checking.mode.default")
        ZubanTypeCheckingMode.MYPY -> ZubanFrontendBundle.message("zuban.type.checking.mode.mypy")
      }
    }

  override val typeCheckingModeComment: String get() = ZubanFrontendBundle.message("zuban.type.checking.mode.comment")
}
