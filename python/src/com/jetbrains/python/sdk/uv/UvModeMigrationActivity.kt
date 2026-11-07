// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.uv

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.python.sdk.pySdkAdditionalData

/**
 * Pins the [UvMode] of every uv SDK saved before the mode existed. See [migrateLegacyUvMode].
 *
 * Runs on every project open. It skips an SDK that stores a file. After the first run it visits only a legacy plain
 * environment again. [UvPackageManagerProvider] does the same for an SDK that the Packages tool window asks about
 * before this activity reaches it.
 */
internal class UvModeMigrationActivity : ProjectActivity, DumbAware {
  override suspend fun execute(project: Project) {
    val legacySdks = ProjectJdkTable.getInstance().allJdks.filter { it.isUv && it.pySdkAdditionalData.requirementsFile == null }
    for (sdk in legacySdks) {
      migrateLegacyUvMode(project, sdk)
    }
  }
}
