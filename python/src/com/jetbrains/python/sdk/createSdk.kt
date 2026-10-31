// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk

import com.jetbrains.python.project.PyProject.Companion.asPyProject
import com.intellij.execution.target.FullPathOnTarget
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.PythonInterpreterProjectRegistry
import com.intellij.python.sdk.backend.getSdkAPI
import com.intellij.python.sdk.backend.pythonInterpreterAsync
import com.jetbrains.python.sdk.legacy.PythonSdkUtil
import com.jetbrains.python.PyBundle
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.Result
import com.jetbrains.python.errorProcessing.MessageError
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.sdk.add.v2.EelFileSystem
import com.jetbrains.python.sdk.add.v2.FileSystem
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor
import com.jetbrains.python.sdk.flavors.PyFlavorAndData
import com.jetbrains.python.sdk.flavors.PyFlavorData
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor
import com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor
import com.jetbrains.python.target.PyTargetAwareAdditionalData
import com.jetbrains.python.target.ui.TargetPanelExtension
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus

// Those are tools to create SDK
// As PyCharm developer, do not call `addSdk` directly: use these tools only.


/**
 * Request to create a sdk either eel or target-based.
 * Once created, call [createSdk]
 */
internal sealed interface SdkCreationRequest<P, D : SdkAdditionalData> {
  val path: P
  val data: D

  data class EelSdk(
    override val path: PythonBinary,
    override val data: PythonSdkAdditionalData,
  ) : SdkCreationRequest<PythonBinary, PythonSdkAdditionalData>

  data class TargetSdk(
    override val path: FullPathOnTarget,
    override val data: PyTargetAwareAdditionalData,
  ) : SdkCreationRequest<FullPathOnTarget, PyTargetAwareAdditionalData>
}

/**
 * Advanced options, do not change them unless you know what you are doing.
 *
 * [setupPaths] means "to calculate various SDK paths", call SDK updater and so on.
 *
 * [associate]: `true` associates a local SDK with its working directory, `false` makes it shared, and `null` lets its
 * environment decide. See [PythonInterpreterProjectRegistry.addPythonInterpreter].
 *
 * [persist] `false` creates an SDK outside the SDK table. Only the old test venv fixture uses it. Remove it when that
 * fixture is gone, because [PythonInterpreterProjectRegistry] adds every SDK to the table.
 */
@ApiStatus.Internal
data class SdkCreationAdvancedOpts(
  internal val persist: Boolean = true,
  val setupPaths: Boolean = true,
  val associate: Boolean? = null,
) {
  companion object {
    val DEFAULT: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts()
  }
}

/**
 * Kinda low-level API to create SDK for [pyProject]. Use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] if
 * possible.
 */
@ApiStatus.Internal
suspend fun createSdk(
  pyProject: PyProject,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> =
  createSdkImpl(pyProject, SdkCreationRequest.EelSdk(pythonBinaryPath.path, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * Kinda low-level API to create SDK for [pyProject]. Use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] if
 * possible.
 */
@ApiStatus.Internal
suspend fun createSdk(
  pyProject: PyProject,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> =
  createSdkImpl(pyProject, SdkCreationRequest.TargetSdk(pythonBinaryPath.pathString, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for a shared interpreter, or for a caller that has no [PyProject] yet. Prefer the [PyProject] overload.
 */
@ApiStatus.Internal
suspend fun createSdk(
  project: Project,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> =
  createSdkImpl(project, SdkCreationRequest.EelSdk(pythonBinaryPath.path, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for a shared interpreter, or for a caller that has no [PyProject] yet. Prefer the [PyProject] overload.
 */
@ApiStatus.Internal
suspend fun createSdk(
  project: Project,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> =
  createSdkImpl(project, SdkCreationRequest.TargetSdk(pythonBinaryPath.pathString, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for the [PyProject] of [moduleOrProject], or a shared interpreter when it has none.
 */
@ApiStatus.Internal
suspend fun createSdk(
  moduleOrProject: ModuleOrProject,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdk(pyProject, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
  else createSdk(moduleOrProject.project, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
}

/**
 * [createSdk] for the [PyProject] of [moduleOrProject], or a shared interpreter when it has none.
 */
@ApiStatus.Internal
suspend fun createSdk(
  moduleOrProject: ModuleOrProject,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdk(pyProject, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
  else createSdk(moduleOrProject.project, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
}

/** The [PyProject] that a new interpreter of this [ModuleOrProject] belongs to, or `null` for a shared one. */
private suspend fun ModuleOrProject.findPyProject(): PyProject? = when (this) {
  is ModuleOrProject.ModuleAndProject -> pyProject ?: module.asPyProject()
  is ModuleOrProject.ProjectOnly -> null
}

/**
 * Please use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] instead
 */
internal suspend fun SdkCreationRequest<*, *>.createSdk(
  moduleOrProject: ModuleOrProject,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): Result<PythonInterpreter, MessageError> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdkImpl(pyProject, this, suggestedSdkName, advancedOpts)
  else createSdkImpl(moduleOrProject.project, this, suggestedSdkName, advancedOpts)
}


/**
 * Use this API only if you do not know SDK type in advance (in most cases you do, please prefer [createSdk]).
 * This function creates and persists SDL
 */
@ApiStatus.Internal
suspend fun createLocalSdkGuessingTypeByPath(
  homePath: PythonBinary,
  moduleOrProject: ModuleOrProject,
  suggestedSdkName: String? = null,
): PyResult<PythonInterpreter> =
  createSdkGuessingTypeByPath(PathHolder.Eel(homePath),
                              EelFileSystem(homePath.getEelDescriptor().toEelApi()),
                              moduleOrProject,
                              null,
                              suggestedSdkName)


/**
 * Use this API only if you do not know SDK type in advance (in most cases you do, please prefer [createSdk])
 */
internal suspend fun <P : PathHolder> createSdkGuessingTypeByPath(
  homePath: P,
  fileSystem: FileSystem<P>,
  moduleOrProject: ModuleOrProject,
  targetPanelExtension: TargetPanelExtension?,
  suggestedSdkName: String? = null,
): PyResult<PythonInterpreter> {
  val flavorAndData = when (homePath) {
    is PathHolder.Eel -> withContext(Dispatchers.IO) {
      val detectedFlavor = PythonSdkFlavor.tryDetectFlavorByLocalPath(homePath.path)
      // We only support flavours without data (i.e. we can't detect conda as we have no conda path)
      val flavor = if (detectedFlavor != null && detectedFlavor.flavorDataClass.isInstance(PyFlavorData.Empty)) {
        @Suppress("UNCHECKED_CAST") // Checked a line above
        detectedFlavor as CPythonSdkFlavor<PyFlavorData.Empty>
      }
      else {
        PythonSdkFlavor.UnknownFlavor.INSTANCE
      }
      PyFlavorAndData(PyFlavorData.Empty, flavor)
    }
    // Target is always UNIX
    is PathHolder.Target -> PyFlavorAndData(PyFlavorData.Empty, UnixPythonSdkFlavor.getInstance())
  }

  val workingDirectory = moduleOrProject.workingDirectory
                         ?: return PyResult.localizedError(PyBundle.message("python.sdk.project.working.directory.not.found"))

  val newPythonInterpreter = fileSystem.setupSdk(
    moduleOrProject = moduleOrProject,
    pythonBinaryPath = homePath,
    sdkAdditionalData = PythonSdkAdditionalData(
      flavorAndData,
      workingDirectory,
    ),
    targetPanelExtension = targetPanelExtension,
    suggestedSdkName = suggestedSdkName
  ).getOr { return it }

  moduleOrProject.project.excludeInnerVirtualEnv(newPythonInterpreter.getSdkAPI())

  return PyResult.success(newPythonInterpreter)
}

private suspend fun createSdkImpl(
  pyProject: PyProject,
  request: SdkCreationRequest<*, *>,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): Result<PythonInterpreter, MessageError> {
  val homePath = request.homePath().getOr { return it }
  if (!advancedOpts.persist) return Result.success(createSdkOutsideTable(homePath, request.data, suggestedSdkName, advancedOpts))
  val interpreter = PythonInterpreterProjectRegistry.getInstance(pyProject.project)
    .addPythonInterpreter(pyProject, homePath, request.pythonData, suggestedSdkName, advancedOpts.setupPaths, advancedOpts.associate)
  return Result.success(interpreter)
}

private suspend fun createSdkImpl(
  project: Project,
  request: SdkCreationRequest<*, *>,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): Result<PythonInterpreter, MessageError> {
  val homePath = request.homePath().getOr { return it }
  if (!advancedOpts.persist) return Result.success(createSdkOutsideTable(homePath, request.data, suggestedSdkName, advancedOpts))
  val interpreter = PythonInterpreterProjectRegistry.getInstance(project)
    .addSharedPythonInterpreter(homePath, request.pythonData, suggestedSdkName, advancedOpts.setupPaths, advancedOpts.associate)
  return Result.success(interpreter)
}

/** The data of the SDK. Both kinds of request carry [PythonSdkAdditionalData]. */
private val SdkCreationRequest<*, *>.pythonData: PythonSdkAdditionalData
  get() = when (this) {
    is SdkCreationRequest.EelSdk -> data
    is SdkCreationRequest.TargetSdk -> data
  }

/** The home path of the SDK: the VFS path of a local binary, or the path on the target. */
private suspend fun SdkCreationRequest<*, *>.homePath(): Result<String, MessageError> {
  val homePath = when (val request = this) {
    is SdkCreationRequest.EelSdk -> {
      val pythonBinaryVirtualFile = withContext(Dispatchers.IO) {
        VirtualFileManager.getInstance().refreshAndFindFileByNioPath(request.path)
      } ?: return PyResult.localizedError(PyBundle.message("python.sdk.python.executable.not.found", request.path))
      pythonBinaryVirtualFile.path
    }
    is SdkCreationRequest.TargetSdk -> request.path
  }
  return Result.success(homePath)
}

/** The SDK of [SdkCreationAdvancedOpts.persist] `false`: it is not in the SDK table, so the registry does not know it. */
private suspend fun createSdkOutsideTable(
  homePath: String,
  data: SdkAdditionalData,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): PythonInterpreter {
  val sdkType = PythonSdkType.getInstance()
  @Suppress("SETUP_SDK_DIRECTLY") // The registry creates every other SDK.
  val sdk = SdkConfigurationUtil.createSdk(PythonSdkUtil.getAllSdks(), homePath, sdkType, data, suggestedSdkName)
  if (advancedOpts.setupPaths) sdkType.setupSdkPaths(sdk)
  return sdk.pythonInterpreterAsync()
}
