// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pyproject.model.evolution

import com.intellij.ide.projectView.actions.MarkRootsManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.getSdkAPI
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import com.jetbrains.python.sdk.baseDir
import com.jetbrains.python.sdk.writePythonSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus

/**
 * Makes [interpreter] the interpreter of this project. It sets the SDK of the module, and of the project when the
 * module is at the project root. It also excludes a virtual environment inside the module. A `null` [interpreter]
 * removes the interpreter.
 *
 * It is the one way to set an interpreter. Tests that cannot use it call `Module.setPythonSdkForTests`.
 *
 * With [waitForSnapshot], it returns only when the snapshot holds [interpreter] for this project. Then a caller that
 * reads the snapshot next sees the new interpreter. Pass `false` when nothing reads the snapshot after the call, such
 * as a UI action.
 */
@ApiStatus.Internal
suspend fun PyProject.setPythonInterpreter(interpreter: PythonInterpreter?, waitForSnapshot: Boolean = true) {
  @Suppress("DEPRECATION") // The module and the project store an SDK.
  val sdk = interpreter?.getSdkAPI()
  val module = residesOnModule
  @Suppress("DEPRECATION") // The project root is the base dir of the module, which PyProject does not expose here.
  if (project.basePath == module.baseDir?.path) {
    edtWriteAction { ProjectRootManager.getInstance(project).projectSdk = sdk }
  }
  module.writePythonSdk(sdk)
  interpreter?.let { excludeInnerVirtualEnv(it) }
  if (waitForSnapshot) EvoPyProjectModel.getInstance(project).awaitInterpreterOf(listOf(this))
}

private suspend fun PyProject.excludeInnerVirtualEnv(interpreter: PythonInterpreter) {
  val root = interpreter.pythonHomePath?.let { VirtualFileManager.getInstance().findFileByNioPath(it) } ?: return
  // modifyRoots takes the write action itself.
  withContext(Dispatchers.EDT) {
    MarkRootsManager.modifyRoots(residesOnModule, arrayOf(root)) { vFile, entry ->
      entry.addExcludeFolder(vFile)
    }
  }
}
