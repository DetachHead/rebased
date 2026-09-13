// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.ex

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.annotations.ApiStatus.Internal

/**
 * Opens the welcome tab when a project restores no editor tabs.
 *
 * A welcome tab that was open at exit restores through the editor state like any other tab, and a tab the user
 * closed stays closed while other tabs remain. So the platform asks this service only when the editor area is
 * empty after the restore.
 *
 * Implementations run earlier than [com.intellij.openapi.startup.ProjectActivity], which is too
 * late for this initialization stage.
 */
@Internal
interface WelcomeScreenTabService {
  /**
   * Opens a welcome tab for the current project when applicable.
   */
  suspend fun openTab()

  companion object {
    val WELCOME_TAB_FILE_MARKER: Key<Boolean> = Key("WELCOME_SCREEN_TAB_FILE")
    /**
     * Returns the project-level implementation.
     */
    fun getInstance(project: Project): WelcomeScreenTabService = project.getService(WelcomeScreenTabService::class.java)
  }
}

/**
 * Default no-op implementation used when no product-specific implementation is registered.
 */
internal class NoWelcomeScreenTabService : WelcomeScreenTabService {
  override suspend fun openTab() = Unit
}

