// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.projectView.impl

import com.intellij.openapi.diagnostic.isControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
suspend fun navigateSafely(
  project: Project,
  navigationRequest: NavigationRequest,
  requestFocus: Boolean,
): Boolean {
  return try {
    NavigationService.getInstance(project).navigate(
      request = navigationRequest,
      options = NavigationOptions.defaultOptions().requestFocus(requestFocus),
    )
  }
  catch (e: Throwable) {
    if (e.isControlFlowException) {
      currentCoroutineContext().ensureActive()
      return false // the navigation itself was canceled, not our job
    }
    else {
      throw e // a genuine exception
    }
  }
}
