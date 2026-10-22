// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.junit5Tests.framework.env

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.edtWriteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.pathString
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.PythonInterpreterProjectRegistry
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

/**
 * An interpreter with environment info. Env fixtures (venv, conda) might use [env] and [project].
 */
@ApiStatus.Internal
class PyInterpreterFixture<ENV : Any>(val interpreter: PythonInterpreter, val env: ENV, val project: Project) {
  override fun equals(other: Any?): Boolean {
    return this === other || other is PyInterpreterFixture<*> && other.interpreter == interpreter
  }

  override fun hashCode(): Int = interpreter.hashCode()
}

/**
 * Creates a mock interpreter of this [PyProject]: not a real python, but with [homePath]. It is added through
 * [PythonInterpreterProjectRegistry]. The SDK has [PythonSdkAdditionalData], because the product treats a Python SDK
 * without it as broken.
 */
@JvmName("pyProjectMockInterpreterFixture")
fun TestFixture<PyProject>.pyMockInterpreterFixture(homePath: TestFixture<Path>): TestFixture<PythonInterpreter> = testFixture {
  val pyProject = this@pyMockInterpreterFixture.init()
  val registry = PythonInterpreterProjectRegistry.getInstance(pyProject.project)
  val interpreter = registry.addMockPythonInterpreter(pyProject, createMockSdk(pyProject.project, homePath.init()))
  initialized(interpreter) {
    registry.removePythonInterpreter(pyProject, interpreter)
  }
}

/**
 * Creates a shared mock interpreter in this project: one that belongs to no [PyProject]. See the [PyProject] overload.
 */
fun TestFixture<Project>.pyMockInterpreterFixture(homePath: TestFixture<Path>): TestFixture<PythonInterpreter> = testFixture {
  val project = this@pyMockInterpreterFixture.init()
  val registry = PythonInterpreterProjectRegistry.getInstance(project)
  val interpreter = registry.addSharedMockPythonInterpreter(createMockSdk(project, homePath.init()))
  initialized(interpreter) {
    registry.removeSharedPythonInterpreter(interpreter)
  }
}

private suspend fun createMockSdk(project: Project, path: Path): Sdk {
  val sdk = ProjectJdkTable.getInstance(project).createSdk("PyMockSDK" + System.currentTimeMillis().toString(), PyMockSdkTypeId)
  val root = withContext(Dispatchers.IO) { VfsUtil.findFile(path, true) } ?: error("No $path")
  edtWriteAction {
    val modificator = sdk.sdkModificator
    modificator.homePath = path.pathString
    modificator.addRoot(root, OrderRootType.CLASSES)
    modificator.sdkAdditionalData = PythonSdkAdditionalData(null)
    modificator.commitChanges()
  }
  return sdk
}
