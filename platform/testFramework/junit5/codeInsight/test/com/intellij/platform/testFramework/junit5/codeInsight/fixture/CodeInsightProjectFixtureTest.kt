// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.testFramework.junit5.codeInsight.fixture

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * Checks that [codeInsightProjectFixture] and [codeInsightFixture] share one project per class and create one fixture per test.
 */
@TestApplication
class CodeInsightProjectFixtureTest {
  companion object {
    private val projectFixture = codeInsightProjectFixture()

    private var previousProject: Project? = null
    private var previousFixture: CodeInsightTestFixture? = null
  }

  private val myFixture by codeInsightFixture(projectFixture)

  @Test
  fun `fixture temp dir is the project directory`() {
    assertEquals(myFixture.project.basePath, myFixture.tempDirPath)
  }

  @RepeatedTest(2)
  fun `project is shared and fixture is new for each test`() {
    val project = previousProject
    val fixture = previousFixture
    if (project != null && fixture != null) {
      assertSame(project, myFixture.project)
      assertNotSame(fixture, myFixture)
    }
    previousProject = myFixture.project
    previousFixture = myFixture
  }
}
