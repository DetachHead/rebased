// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.util

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.plugins.groovy.LightGroovyTestCase
import org.junit.rules.ExternalResource

class FixtureRule(descriptor: LightProjectDescriptor, path: String) : ExternalResource() {

  private val testCase = FixtureTestCase(descriptor, path)

  val fixture: JavaCodeInsightTestFixture get() = testCase.fixture

  override fun before(): Unit = testCase.setUp()

  override fun after(): Unit = testCase.tearDown()

  @Suppress("JUnitMalformedDeclaration")
  private class FixtureTestCase(
    private val descriptor: LightProjectDescriptor,
    private val path: String,
  ) : LightGroovyTestCase() {
    override fun getProjectDescriptor() = descriptor
    override fun getTestDataPath(): String = TestUtils.getAbsoluteTestDataPath() + path
  }
}
