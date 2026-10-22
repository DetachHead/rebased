// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.module.Module
import com.jetbrains.python.PyInternalExecApi
import com.jetbrains.python.module.PyModuleService
import com.jetbrains.python.module.PySdkToFacetSetter
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.PyProject.Companion.asPyProject
import org.jetbrains.annotations.ApiStatus

/**
 * The [PyProject] of this module, for a caller that sets up an interpreter for it. A module of another IDE, such as
 * GoLand, is not a Python module. It gets the Python facet first, so it becomes a [PyProject].
 *
 * Call it only when the user sets up an interpreter for this module. Other code, such as the status bar or an
 * inspection, uses [asPyProject], because a module without the facet is not a Python project.
 *
 * `null` means the module cannot be a [PyProject], for example because it has no content root.
 */
@OptIn(PyInternalExecApi::class)
@ApiStatus.Internal
suspend fun Module.asPyProjectAddingFacet(): PyProject? {
  asPyProject()?.let { return it }
  if (PyModuleService.getInstance(project).isPythonModule(this)) return null
  val facetSetter = ApplicationManager.getApplication().serviceOrNull<PySdkToFacetSetter>() ?: return null
  edtWriteAction {
    if (!isDisposed) facetSetter.setPythonSdkToFacet(this, null)
  }
  return asPyProject()
}
