// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.zuban

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.python.lsp.core.LSP_TOOLS_STORAGE_FILE
import com.intellij.python.lsp.core.PyLspToolConfiguration
import com.intellij.python.zuban.common.ZubanTypeCheckingMode
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * The settings of [ZubanPyTool]. Zuban gives inlay hints for inferred types and hovers with types, so both
 * are on. The [typeCheckingMode] reaches the server through [zubanInitializationOptions].
 */
@Service(Service.Level.PROJECT)
@State(
  name = "ZubanConfiguration",
  storages = [Storage(LSP_TOOLS_STORAGE_FILE)]
)
data class ZubanConfiguration(
  override var inlayHints: Boolean? = true,
  override var completions: Boolean? = true,
  override var documentation: Boolean? = true,
  var typeCheckingMode: ZubanTypeCheckingMode = ZubanTypeCheckingMode.AUTO,
) : PyLspToolConfiguration<ZubanConfiguration>() {
  override fun loadState(state: ZubanConfiguration) {
    XmlSerializerUtil.copyBean(state, this)
  }
}
