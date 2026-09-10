// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.platform.pluginSystem.parser.impl.PluginDescriptorReaderContext
import com.intellij.platform.pluginSystem.parser.impl.parsePluginXml
import com.intellij.util.xml.dom.NoOpXmlInterner
import com.intellij.util.xml.dom.XmlInterner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Smoke test: loads this module's `plugin.xml` descriptor using the platform's descriptor
 * parser ([parsePluginXml]) and asserts it parses without errors, with the expected
 * id/name/resource-bundle. This deliberately avoids a full IDE bootstrap
 * (e.g. [com.intellij.testFramework.fixtures.BasePlatformTestCase]) since the parser itself
 * is a pure function over an XML stream - no application services are needed to validate that
 * the descriptor is well-formed. Mirrors the parsing approach used by
 * `PluginXmlParserTest` in `platform/pluginSystem/parser/impl`.
 */
class GitReviewCommentsPluginXmlTest {
  @Test
  fun `plugin xml parses without errors`() {
    val pluginXml = findModuleFile("resources/META-INF/plugin.xml")

    val context = object : PluginDescriptorReaderContext {
      override val interner: XmlInterner = NoOpXmlInterner
      override val isMissingIncludeIgnored: Boolean = true
    }
    val descriptor = pluginXml.inputStream().use { input ->
      parsePluginXml(input, pluginXml.path, context, null).build()
    }

    assertEquals("intellij.vcs.git.review.comments", descriptor.id)
    assertEquals("Git Review Comments", descriptor.name)
    assertEquals("messages.GitReviewCommentsBundle", descriptor.resourceBundleBaseName)
  }

  companion object {
    /**
     * Locates a file inside the `plugins/git-review-comments` module directory regardless of
     * the working directory the test is executed from (Bazel `jps_test` runtime, IDE test
     * runner, or a plain JVM invocation all differ here).
     */
    private fun findModuleFile(relativePath: String): File {
      var dir = File(".").absoluteFile
      var probe = File(dir, "plugins/git-review-comments/$relativePath")
      var guard = 0
      while (!probe.exists() && guard < 12) {
        dir = dir.parentFile ?: break
        probe = File(dir, "plugins/git-review-comments/$relativePath")
        guard++
      }
      assertTrue("could not locate $relativePath under plugins/git-review-comments (searched up from ${File(".").absolutePath})", probe.exists())
      return probe
    }
  }
}
