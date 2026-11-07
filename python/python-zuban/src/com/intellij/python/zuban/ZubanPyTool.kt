// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.zuban

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Version
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.stopAndRestartClientsIfNeeded
import com.intellij.platform.lsp.api.stopClients
import com.intellij.python.lsp.core.PyLspTool
import com.intellij.python.lsp.core.common.PyLspToolConfigurationDto
import com.intellij.python.pytools.backend.PyTool
import com.intellij.python.pytools.backend.isEnabledOn
import com.intellij.python.zuban.common.ZubanTypeCheckingMode
import com.intellij.python.zuban.common.icons.PythonZubanCommonIcons
import com.intellij.util.IconUtil
import com.jetbrains.python.packaging.PyPackageName
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

/**
 * [Zuban](https://zubanls.com/) — a fast, Mypy-compatible Python type checker and language server,
 * written in Rust.
 */
@ApiStatus.Internal
class ZubanPyTool : PyLspTool<ZubanConfiguration>() {
  override val lspServerName: String = "Zuban"
  override val icon: Icon = IconUtil.resizeSquared(PythonZubanCommonIcons.Zuban, 16)
  override val packageName: PyPackageName = PyPackageName.from("zuban")

  /** The first release that reads `pythonExecutable` from the initialization options, see [zubanInitializationOptions]. */
  override val minimumSupportedVersion: Version = Version(0, 8, 1)

  override fun configuration(project: Project): ZubanConfiguration = project.service()

  /** Zuban takes one `pythonExecutable` for all its workspace folders. */
  override val serverNeedsOneInterpreter: Boolean get() = true

  override fun configurationState(project: Project): PyLspToolConfigurationDto =
    super.configurationState(project).copy(typeCheckingMode = configuration(project).typeCheckingMode.value)

  /** Zuban reads its mode only at the start, so a new mode restarts the servers. */
  override fun applyConfigurationState(project: Project, state: PyLspToolConfigurationDto) {
    super.applyConfigurationState(project, state)
    val configuration = configuration(project)
    val mode = ZubanTypeCheckingMode.of(state.typeCheckingMode) ?: return
    if (mode == configuration.typeCheckingMode) return
    configuration.typeCheckingMode = mode
    if (isEnabledOn(project)) {
      LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded<ZubanLspIntegrationProvider>()
    }
  }

  override fun onEnabledChanged(project: Project, enabled: Boolean) {
    val manager = LspClientManager.getInstance(project)
    if (enabled) manager.startClientsIfNeeded(ZubanLspIntegrationProvider::class.java)
    else manager.stopClients<ZubanLspIntegrationProvider>()
  }

  /**
   * A running server keeps the binary it started with, and the interpreter that its initialization
   * options named. Zuban reads that interpreter only at the start, so a restart is the only way to give
   * it a new one.
   */
  override fun onExecutableChanged(project: Project) {
    if (!isEnabledOn(project)) return
    LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded<ZubanLspIntegrationProvider>()
  }
}

/** The registered [ZubanPyTool]. */
@ApiStatus.Internal
fun zubanPyTool(): ZubanPyTool = PyTool.EP_NAME.findExtensionOrFail(ZubanPyTool::class.java)
