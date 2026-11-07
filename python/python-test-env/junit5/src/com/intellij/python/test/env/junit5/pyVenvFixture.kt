// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.test.env.junit5

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.python.junit5Tests.framework.env.SdkFixture
import com.intellij.python.sdk.backend.getSdkAPI
import com.intellij.python.test.env.core.PyEnvironment
import com.intellij.python.venv.createVenv
import com.intellij.python.venv.createVenvAdditionalData
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.jetbrains.python.getOrThrow
import com.jetbrains.python.sdk.SdkCreationAdvancedOpts
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.jetbrains.python.sdk.createSdk
import com.jetbrains.python.sdk.pythonSdk
import com.jetbrains.python.sdk.setAssociationToModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.project.PyProject
import com.intellij.python.pyproject.model.evolution.setPythonInterpreter
import com.intellij.python.junit5Tests.framework.env.PyInterpreterFixture
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.PythonInterpreterProjectRegistry

/**
 * Create virtual env in [where]. If [addToSdkTable] then also added to the project jdk table
 */
fun TestFixture<SdkFixture<PyEnvironment>>.pyVenvFixture(
  where: TestFixture<Path>,
  addToSdkTable: Boolean,
  moduleFixture: TestFixture<Module>? = null,
): TestFixture<Sdk> = testFixture {
  val env = this@pyVenvFixture.init().env
  withContext(Dispatchers.EDT) {
    val module = moduleFixture?.init()
    val workingDirectory = where.init()
    val venvDir = workingDirectory.resolve(".venv")
    val venvPython = createVenv(env.pythonPath, venvDir).getOrThrow()
    val additionalData = createVenvAdditionalData(workingDirectory)
    if (module == null) {
      // With no module this fixture stands for a *shared* venv, so it must not keep the association a new SDK derives
      // from its working directory: sortForExistingEnvironment only treats an unassociated SDK as SHARED_VENVS.
      additionalData.associatedModulePath = null
    }
    // The SDK table is global, so a fixture with no module adds its SDK through the default project.
    val project = module?.project ?: ProjectManager.getInstance().defaultProject
    val interpreter = createSdk(
      project,
      PathHolder.Eel(venvPython),
      additionalData,
      advancedOpts = SdkCreationAdvancedOpts(persist = addToSdkTable, associate = if (module == null) false else null),
    ).orThrow()
    val sdk = interpreter.getSdkAPI()
    if (addToSdkTable) {
      if (module != null) {
        module.pythonSdk = sdk
        sdk.setAssociationToModule(module)
      }
    }
    initialized(sdk) {
      edtWriteAction {
        ProjectJdkTable.getInstance().removeJdk(sdk)
      }
    }
  }
}

/**
 * Creates a virtual env in [where] and adds its interpreter to the project of the interpreter fixture. With a
 * [pyProjectFixture], that Python project also gets the interpreter.
 */
fun TestFixture<PyInterpreterFixture<PyEnvironment>>.pyVenvFixture(
  where: TestFixture<Path>,
  pyProjectFixture: TestFixture<PyProject>? = null,
): TestFixture<PythonInterpreter> = testFixture {
  val interpreterFixture = this@pyVenvFixture.init()
  val project = interpreterFixture.project
  val pyProject = pyProjectFixture?.init()
  val workingDirectory = where.init()
  val venvDir = workingDirectory.resolve(".venv")
  val venvPython = withContext(Dispatchers.EDT) { createVenv(interpreterFixture.env.pythonPath, venvDir).getOrThrow() }
  // With a Python project the venv belongs to it, so its SDK is associated with the project at creation.
  val additionalData = if (pyProject != null) createVenvAdditionalData(pyProject.residesOnModule).getOrThrow() else createVenvAdditionalData(workingDirectory)
  // With no Python project this fixture stands for a *shared* venv, so its SDK gets no association:
  // sortForExistingEnvironment only treats an unassociated SDK as SHARED_VENVS.
  val opts = SdkCreationAdvancedOpts(associate = if (pyProject == null) false else null)
  val interpreter = if (pyProject != null) {
    createSdk(pyProject, PathHolder.Eel(venvPython), additionalData, advancedOpts = opts).orThrow()
  }
  else {
    createSdk(project, PathHolder.Eel(venvPython), additionalData, advancedOpts = opts).orThrow()
  }
  pyProject?.setPythonInterpreter(interpreter)
  initialized(interpreter) {
    val registry = PythonInterpreterProjectRegistry.getInstance(project)
    if (pyProject != null) {
      pyProject.setPythonInterpreter(null)
      registry.removePythonInterpreter(pyProject, interpreter)
    }
    else {
      registry.removeSharedPythonInterpreter(interpreter)
    }
  }
}

/**
 * Creates a virtual env in [where] and gives its Python binary. It adds no interpreter to the project, so the venv is
 * only on disk, as one that the IDE has to detect.
 */
fun TestFixture<PyInterpreterFixture<PyEnvironment>>.pyVenvOnDiskFixture(where: TestFixture<Path>): TestFixture<PythonBinary> =
  testFixture {
    val interpreterFixture = this@pyVenvOnDiskFixture.init()
    val venvDir = where.init().resolve(".venv")
    val venvPython = withContext(Dispatchers.EDT) { createVenv(interpreterFixture.env.pythonPath, venvDir).getOrThrow() }
    initialized(venvPython) {}
  }
