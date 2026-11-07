// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.maven.testFramework.fixtures.MavenVersionArguments
import com.intellij.maven.testFramework.fixtures.createModulePom
import com.intellij.maven.testFramework.fixtures.importProjectsAsync
import com.intellij.maven.testFramework.fixtures.initProjectsManager
import com.intellij.maven.testFramework.fixtures.mavenImportingFixture
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.ArgumentsSource

/**
 * Verifies [MavenModelProblemFilter] resolution that needs a real imported reactor:
 *  - "own pom" problems, whose location Maven prints without a model id/source, resolved via the
 *    preceding `building the effective model for <gav>` header;
 *  - cross-model problems, whose location carries the offending module's model id but no source path.
 *
 * Parsing, highlight spans and line/column mapping are covered by the light `MavenModelProblemFilterTest`.
 */
@TestApplication
@ParameterizedClass
@ArgumentsSource(MavenVersionArguments::class)
class MavenModelProblemNavigationTest(mavenVersion: String, modelVersion: String) {

  private val maven by mavenImportingFixture(
    mavenVersion = mavenVersion,
    modelVersion = modelVersion,
  )

  @BeforeEach
  fun setUp() {
    maven.initProjectsManager(false)
  }

  @Test
  fun testResolvesOwnPomProblemViaModelHeader() = runBlocking {
    val (m1, _) = importReactor()

    val filter = MavenModelProblemFilter(maven.project)
    // the header tells the filter which module the following, location-only problem belongs to
    assertNull(filter.applyFilter(header("test:m1:jar:1"), 0))

    val line = "[WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-jar-plugin is missing. " +
               "@ line 3, column 12"
    val result = filter.applyFilter(line, line.length)
    assertNotNull(result)
    val item = result!!.resultItems.single()
    val info = assertInstanceOf(OpenFileHyperlinkInfo::class.java, item.hyperlinkInfo)
    assertEquals(m1, info.virtualFile)
    assertEquals(line.indexOf("@ "), item.highlightStartOffset)
    assertEquals(line.length, item.highlightEndOffset)
  }

  @Test
  fun testResolvesOtherModuleProblemViaModelId() = runBlocking {
    val (_, m2) = importReactor()

    val filter = MavenModelProblemFilter(maven.project)
    // no header seen; the location carries the offending module's model id but no source path
    val line = "[WARNING] 'dependencies.dependency.version' for x:y:jar is missing. @ test:m2:jar:1, line 4, column 5"
    val result = filter.applyFilter(line, line.length)
    assertNotNull(result)
    val info = assertInstanceOf(OpenFileHyperlinkInfo::class.java, result!!.resultItems.single().hyperlinkInfo)
    assertEquals(m2, info.virtualFile)
  }

  @Test
  fun testUnknownModelWithoutSourceIsNotResolved() = runBlocking {
    importReactor()

    val filter = MavenModelProblemFilter(maven.project)
    val line = "[WARNING] 'x' is missing. @ test:absent:jar:1, line 2, column 3"
    assertNull(filter.applyFilter(line, line.length))
  }

  private fun header(gav: String): String =
    "[WARNING] Some problems were encountered while building the effective model for $gav"

  private suspend fun importReactor(): Pair<VirtualFile, VirtualFile> {
    val m1 = maven.createModulePom("m1", """
      <groupId>test</groupId>
      <artifactId>m1</artifactId>
      <version>1</version>
      """.trimIndent())
    val m2 = maven.createModulePom("m2", """
      <groupId>test</groupId>
      <artifactId>m2</artifactId>
      <version>1</version>
      """.trimIndent())
    maven.importProjectsAsync(m1, m2)
    return m1 to m2
  }
}
