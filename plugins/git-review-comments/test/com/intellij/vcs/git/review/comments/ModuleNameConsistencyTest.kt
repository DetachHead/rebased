// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sanity check preventing drift between this module's two build-system descriptors: the
 * IntelliJ project model (`.iml`, whose module name is its file's base name) and Bazel
 * (`BUILD.bazel`'s `module_name = "..."` attributes). If these two ever disagree, JPS and
 * Bazel builds silently diverge on what "this module" means.
 */
class ModuleNameConsistencyTest {
  @Test
  fun `BUILD bazel module_name matches the iml file name`() {
    val moduleDir = findModuleDir()
    val imlFiles = moduleDir.listFiles { f -> f.isFile && f.name.endsWith(".iml") }.orEmpty()
    assertTrue("expected exactly one .iml file in $moduleDir, found ${imlFiles.map { it.name }}", imlFiles.size == 1)
    val expectedModuleName = imlFiles.single().name.removeSuffix(".iml")

    val buildBazel = File(moduleDir, "BUILD.bazel")
    assertTrue("BUILD.bazel not found at $buildBazel", buildBazel.exists())
    val buildBazelText = buildBazel.readText()

    val moduleNameRegex = Regex("""module_name\s*=\s*"([^"]+)"""")
    val moduleNamesInBazel = moduleNameRegex.findAll(buildBazelText).map { it.groupValues[1] }.toSet()

    assertTrue(
      "expected BUILD.bazel to declare module_name = \"$expectedModuleName\" (from ${imlFiles.single().name}), " +
        "but found $moduleNamesInBazel",
      moduleNamesInBazel.isNotEmpty() && moduleNamesInBazel.all { it == expectedModuleName },
    )
  }

  companion object {
    private fun findModuleDir(): File {
      var dir = File(".").absoluteFile
      var probe = File(dir, "plugins/git-review-comments")
      var guard = 0
      while (!probe.isDirectory && guard < 12) {
        dir = dir.parentFile ?: break
        probe = File(dir, "plugins/git-review-comments")
        guard++
      }
      assertTrue("could not locate plugins/git-review-comments (searched up from ${File(".").absolutePath})", probe.isDirectory)
      return probe
    }
  }
}
