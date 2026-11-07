package com.jetbrains.python.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.jetbrains.python.module.PyModuleService
import com.jetbrains.python.sdk.legacy.PythonSdkUtil
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import com.intellij.python.sdk.backend.pythonInterpreter

/**
 * Returns the Python SDK configured for this module, or `null` if none is set.
 *
 * Unlike [pythonSdk], this method suspends until the project model is fully loaded
 * before resolving the SDK, so it is safe to call during startup.
 */
@ApiStatus.Experimental
suspend fun Module.findPythonSdk(): Sdk? {
  return PyModuleService.getInstance(getProject()).findPythonSdkWaitingForProjectModel(this)
}

/**
 * The Python SDK configured for this module.
 *
 * **Startup caveat:** the getter may return `null` when a Python SDK *is* configured but hasn't
 * resolved yet (e.g., the SDK table is still loading from a stale workspace model cache).
 * Prefer the suspended [findPythonSdk] extension in coroutine contexts.
 */
var Module.pythonSdk: Sdk?
  @ApiStatus.Obsolete
  get() = PythonSdkUtil.findPythonSdk(this)

  /**
   * For tests only. A test module is often not a Python project, and a test SDK is often a mock, so a test cannot use
   * `PyProject.setPythonInterpreter`. Production code calls that function, which is the one way to set an interpreter.
   */
  @TestOnly
  @ApiStatus.Internal
  @RequiresBackgroundThread(generateAssertion = false)
  set(newSdk) = writePythonSdk(newSdk)

/**
 * Writes [newSdk] to this module and notifies [PySdkListener]. The one low-level writer of a module SDK.
 *
 * Production code calls `PyProject.setPythonInterpreter`, which also waits for the snapshot. Tests set [pythonSdk].
 *
 * Must be called under [withSdkConfigurationLock] to prevent concurrent Module/SDK changes.
 */
@ApiStatus.Internal
@RequiresBackgroundThread(generateAssertion = false)
fun Module.writePythonSdk(newSdk: Sdk?) {
  val prevSdk = pythonSdk
  thisLogger.info("Setting PythonSDK $newSdk to module $this")
  newSdk?.pythonInterpreter(forceRefresh = true)
  // The daemon restarts when the snapshot holds the new interpreter. See EvoPyProjectModel.
  ApplicationManager.getApplication().invokeAndWait {
    WriteAction.runAndWait<Throwable> {
      PyModuleService.getInstance(project).setPythonSdk(this, newSdk)
    }
  }
  ApplicationManager.getApplication().messageBus.syncPublisher(PySdkListener.TOPIC).moduleSdkUpdated(this, prevSdk, newSdk)
}

private val thisLogger = fileLogger()
