// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.sdk.backend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.legacy.PythonSdkUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import com.jetbrains.python.PyNames
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.sdk.PyRemoteSdkAdditionalDataMarker
import com.jetbrains.python.sdk.pythonBinaryPath
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/**
 * The interpreters of each [PyProject] of this project, and the one way to add or remove a Python SDK.
 *
 * Every function takes the [PyProject] that the interpreter belongs to. The interpreters will be stored for each
 * [PyProject]. For now the storage is the SDK table of the application, so each [PyProject] sees every Python SDK, and
 * [addSdk] and [removeSdk] write that table. A global registry will hold the shared interpreters.
 */
@Service(Service.Level.PROJECT)
@ApiStatus.Internal
class PythonInterpreterProjectRegistry(@Suppress("UNUSED_PARAMETER") project: Project, scope: CoroutineScope) {
  private val state = MutableStateFlow<Set<PythonInterpreter>?>(null)

  init {
    // A change made outside this registry, for example by the platform Settings, reaches the set through this listener.
    scope.launch {
      sdkTableChanges()
        .onStart { emit(Unit) }
        .conflate()
        .collect { state.value = compute() }
    }
  }

  /** The interpreters of [pyProject]. Waits for the first computation. */
  suspend fun interpreters(@Suppress("UNUSED_PARAMETER") pyProject: PyProject): Set<PythonInterpreter> =
    state.filterNotNull().first()

  /** The interpreters of every [PyProject]. Waits for the first computation. For [Project.findPythonInterpreter]. */
  internal suspend fun allInterpreters(): Set<PythonInterpreter> = state.filterNotNull().first()

  /** [allInterpreters] without the wait, or `null` while the first computation is still running. */
  internal fun allInterpretersOrNull(): Set<PythonInterpreter>? = state.value

  /**
   * Creates a Python SDK for the interpreter at [homePath] and adds it to [pyProject]. [homePath] is a local path or a
   * path on a target.
   *
   * A local interpreter that already has an SDK keeps it. That SDK takes [data] and keeps its name. A remote one always
   * gets a new SDK, because its path does not say which machine holds the file.
   *
   * With [setupPaths], it also sets up the SDK paths. When that fails, it removes the SDK it added.
   *
   * [associate] decides the association of a local SDK with its working directory: `true` associates it, `false`
   * makes it shared, and `null` lets its environment decide, see [PythonEnvironment.requiresAssociation].
   */
  suspend fun addPythonInterpreter(
    @Suppress("UNUSED_PARAMETER") pyProject: PyProject,
    homePath: String,
    data: PythonSdkAdditionalData,
    suggestedName: String? = null,
    setupPaths: Boolean = true,
    associate: Boolean? = null,
  ): PythonInterpreter = addInterpreter(homePath, data, suggestedName, setupPaths, associate)

  /**
   * [addPythonInterpreter] for a shared interpreter, which belongs to no [PyProject]. A global registry will hold these.
   * Remove it with [removeSharedPythonInterpreter].
   */
  @ApiStatus.Obsolete
  suspend fun addSharedPythonInterpreter(
    homePath: String,
    data: PythonSdkAdditionalData,
    suggestedName: String? = null,
    setupPaths: Boolean = true,
    associate: Boolean? = null,
  ): PythonInterpreter = addInterpreter(homePath, data, suggestedName, setupPaths, associate)

  private suspend fun addInterpreter(
    homePath: String,
    data: PythonSdkAdditionalData,
    suggestedName: String?,
    setupPaths: Boolean,
    associate: Boolean?,
  ): PythonInterpreter {
    val sdkType = SdkType.findByName(PyNames.PYTHON_SDK_ID_NAME) ?: error("The Python SDK type is not registered")
    val existingSdks = PythonSdkUtil.getAllSdks()
    if (data !is PyRemoteSdkAdditionalDataMarker) {
      data.associate(Path.of(homePath), associate)
      findSdkToAdopt(Path.of(homePath), existingSdks) { suggestedName ?: sdkType.suggestSdkName(null, homePath) }?.let { sdk ->
        edtWriteAction {
          val modificator = sdk.sdkModificator
          modificator.sdkAdditionalData = data
          modificator.commitChanges()
        }
        return sdk.pythonInterpreterAsync()
      }
    }

    @Suppress("SETUP_SDK_DIRECTLY") // The registry is the only place that creates a Python SDK.
    val sdk = SdkConfigurationUtil.createSdk(existingSdks, homePath, sdkType, data, suggestedName)
    val interpreter = addSdk(sdk)
    if (setupPaths) {
      try {
        sdkType.setupSdkPaths(sdk)
      }
      catch (e: Throwable) {
        // Do not leave a broken SDK in the table.
        withContext(NonCancellable) { removeSdk(sdk) }
        throw e
      }
    }
    return interpreter
  }

  /**
   * Adds a prebuilt mock [sdk] to [pyProject] for a test. A mock SDK has no real interpreter, so [addPythonInterpreter]
   * cannot create it. Remove it with [removePythonInterpreter].
   */
  @TestOnly
  suspend fun addMockPythonInterpreter(@Suppress("UNUSED_PARAMETER") pyProject: PyProject, sdk: Sdk): PythonInterpreter =
    addSdk(sdk)

  /** [addMockPythonInterpreter] for a shared interpreter. Remove it with [removeSharedPythonInterpreter]. */
  @TestOnly
  suspend fun addSharedMockPythonInterpreter(sdk: Sdk): PythonInterpreter = addSdk(sdk)

  /** Removes [interpreter] from [pyProject]. Returns when the interpreters of [pyProject] no longer hold it. */
  suspend fun removePythonInterpreter(@Suppress("UNUSED_PARAMETER") pyProject: PyProject, interpreter: PythonInterpreter) {
    removeSdk(interpreter.sdk)
  }

  /** Removes a shared [interpreter]. Returns when the registry no longer holds it. */
  @ApiStatus.Obsolete
  suspend fun removeSharedPythonInterpreter(interpreter: PythonInterpreter) {
    removeSdk(interpreter.sdk)
  }

  /**
   * Adds [sdk] to the SDK table and returns its interpreter. The table event recomputes the set, and this function
   * returns when the set holds the interpreter, so a caller that reads the set next finds it.
   *
   * The name check and the add are one write action, so two parallel calls cannot add two SDKs with one name.
   */
  private suspend fun addSdk(sdk: Sdk): PythonInterpreter {
    edtWriteAction {
      makeSureNameIsUnique(sdk)
      ProjectJdkTable.getInstance().addJdk(sdk)
    }
    return state.mapNotNull { set -> set?.firstOrNull { it.isFor(sdk) } }.first()
  }

  /** Removes [sdk] from the SDK table. Returns when the set no longer holds its interpreter. */
  private suspend fun removeSdk(sdk: Sdk) {
    edtWriteAction { ProjectJdkTable.getInstance().removeJdk(sdk) }
    state.first { set -> set != null && set.none { it.isFor(sdk) } }
  }

  private suspend fun compute(): Set<PythonInterpreter> =
    PythonSdkUtil.getAllSdks()
      .filter { it.sdkAdditionalData is PythonSdkAdditionalData }
      .mapTo(mutableSetOf()) { it.pythonInterpreterAsync() }

  /** An event for each change of the SDK table. The table is small, so every change starts a recompute. */
  private fun sdkTableChanges(): Flow<Unit> = callbackFlow {
    val connection = ApplicationManager.getApplication().messageBus.connect(this)
    connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
      override fun jdkAdded(jdk: Sdk) = trySend(Unit).let { }

      override fun jdkRemoved(jdk: Sdk) = trySend(Unit).let { }

      override fun jdkNameChanged(jdk: Sdk, previousName: String) = trySend(Unit).let { }
    })
    awaitClose { connection.disconnect() }
  }

  companion object {
    fun getInstance(project: Project): PythonInterpreterProjectRegistry = project.service()
  }
}

/**
 * The interpreter of [sdk] among the interpreters of this [PyProject]. Waits for the first computation.
 * For a caller that the platform passes an [Sdk]. `null` means that this [PyProject] cannot use [sdk].
 */
@ApiStatus.Internal
suspend fun PyProject.findPythonInterpreter(sdk: Sdk): PythonInterpreter? =
  PythonInterpreterProjectRegistry.getInstance(project).interpreters(this).firstOrNull { it.isFor(sdk) }

/**
 * The interpreter of [sdk] in any [PyProject] of this project. Waits for the first computation.
 *
 * A bridge for a caller that has an [Sdk] and no [PyProject]. Prefer [PyProject.findPythonInterpreter]. While the
 * storage is the SDK table, it also finds [sdk] in a project with no [PyProject].
 */
@ApiStatus.Internal
suspend fun Project.findPythonInterpreter(sdk: Sdk): PythonInterpreter? =
  PythonInterpreterProjectRegistry.getInstance(this).allInterpreters().firstOrNull { it.isFor(sdk) }

/** [Project.findPythonInterpreter] for [sdk] without the wait. `null` also before the first computation. */
@ApiStatus.Internal
fun Project.findPythonInterpreterIfReady(sdk: Sdk): PythonInterpreter? =
  PythonInterpreterProjectRegistry.getInstance(this).allInterpretersOrNull()?.firstOrNull { it.isFor(sdk) }

/**
 * The usual SDK that already stands for the interpreter at [pythonBinaryPath], or `null` when none does. A usual SDK is
 * one whose data carries no [PyRemoteSdkAdditionalDataMarker].
 *
 * The IDE keeps one usual SDK for each interpreter, not one for each interpreter and tool. A `.venv` that poetry
 * created, and uv then adopted, is one environment. A second SDK for it reads as a second interpreter in every list the
 * IDE shows.
 *
 * Older builds also keyed on the tool, so one path can carry several SDKs already. The SDK named [preferredName] wins,
 * because that is the name a new SDK gets here. If no SDK has that name, the first one wins, so the answer is stable.
 * [preferredName] is read only when there is more than one candidate, because it reads the file system.
 */
private fun findSdkToAdopt(pythonBinaryPath: PythonBinary, existingSdks: List<Sdk>, preferredName: () -> String): Sdk? {
  // Compared as paths, not as strings, because `c:\windows` and `c:/Windows` name one file.
  val candidates = existingSdks.filter {
    it.sdkAdditionalData !is PyRemoteSdkAdditionalDataMarker && it.pythonBinaryPath().successOrNull == pythonBinaryPath
  }
  if (candidates.size < 2) return candidates.firstOrNull()
  val name = preferredName()
  return candidates.firstOrNull { it.name == name } ?: candidates.first()
}

/**
 * Associates a new local SDK with its working directory, or makes it shared, as [associate] says. With `null`, the
 * environment decides: an environment that belongs to one project is associated, and a system Python or a base conda
 * install is shared. See [PythonEnvironment.requiresAssociation].
 *
 * A remote SDK is associated by the [PythonSdkAdditionalData] constructor.
 */
private suspend fun PythonSdkAdditionalData.associate(pythonBinaryPath: PythonBinary, associate: Boolean?) {
  if (!hasValidWorkingDirectory()) return
  val requiresAssociation = associate ?: withContext(Dispatchers.IO) {
    pythonBinaryPath.detectPythonEnvironment().successOrNull?.requiresAssociation
  } ?: false
  setAssociatedModulePath(if (requiresAssociation) workingDirectory.toString() else null)
}

@RequiresWriteLock(generateAssertion = false /* IJPL-115548 */)
private fun makeSureNameIsUnique(sdk: Sdk) {
  val name = sdk.name
  var i = 1
  while (ProjectJdkTable.getInstance().findJdk(sdk.name) != null) {
    val m = sdk.sdkModificator
    m.name = "$name@$i"
    i += 1
    m.commitChanges()
  }
}
