// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.uv.packaging

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.python.requirements.RequirementsFileType
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.packaging.management.PythonPackageManagerAction
import com.jetbrains.python.packaging.management.getPythonPackageManager
import com.jetbrains.python.sdk.uv.UvPipPackageManager

/** An action on a requirements file of an SDK in pip mode. */
internal sealed class UvPipPackageManagerAction : PythonPackageManagerAction<UvPipPackageManager, String>() {
  override fun isWatchedFile(virtualFile: VirtualFile?): Boolean = virtualFile?.fileType is RequirementsFileType

  override fun getManager(e: AnActionEvent): UvPipPackageManager? = e.getPythonPackageManager()
}

internal class UvPipInstallRequirementsAction : UvPipPackageManagerAction() {
  override suspend fun execute(e: AnActionEvent, manager: UvPipPackageManager): PyResult<Unit> {
    val requirementsFile = e.getData(PlatformDataKeys.VIRTUAL_FILE) ?: return PyResult.success(Unit)
    return manager.installRequirements(requirementsFile)
  }
}

internal class UvPipSyncRequirementsAction : UvPipPackageManagerAction() {
  override suspend fun execute(e: AnActionEvent, manager: UvPipPackageManager): PyResult<Unit> {
    val requirementsFile = e.getData(PlatformDataKeys.VIRTUAL_FILE) ?: return PyResult.success(Unit)
    return manager.syncRequirements(requirementsFile)
  }
}
