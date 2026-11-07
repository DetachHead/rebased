// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion.tracker

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.ml.CompletionEnvironment
import com.intellij.codeInsight.completion.ml.ContextFeatureProvider
import com.intellij.codeInsight.completion.ml.ContextFeatures
import com.intellij.codeInsight.completion.ml.ElementFeatureProvider
import com.intellij.codeInsight.completion.ml.MLFeatureValue
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.Key
import com.intellij.stats.completion.events.CompletionStartedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MLFeaturesLoggingTest : CompletionLoggingTestBase() {
  @Test
  fun `test context features logged`(): Unit = doTest { startedEvent ->
    val contextFactors = startedEvent.contextFactors
    assertTrue(contextFactors.isNotEmpty())
    assertEquals("1", contextFactors[contextFactorName("binary")])
    assertEquals("1.0", contextFactors[contextFactorName("float")])
    assertEquals("VALUE1", contextFactors[contextFactorName("categorical")])
    assertEquals(TestContextFeatureProvider::class.java.simpleName, contextFactors[contextFactorName("classSimpleName")])
    assertEquals(TestContextFeatureProvider::class.java.name, contextFactors[contextFactorName("classFullName")])
  }

  @Test
  fun `test element features logged`(): Unit = doTest { startedEvent ->
    val firstItem = startedEvent.newCompletionListItems[0]
    val relevance = firstItem.relevance!!
    assertEquals("0", relevance[elementFactorName("binary")])
    assertEquals("2.0", relevance[elementFactorName("float")])
    assertEquals("VALUE2", relevance[elementFactorName("categorical")])
  }

  @Test
  fun `test element features provider can use context features`(): Unit = doTest { startedEvent ->
    val firstItem = startedEvent.newCompletionListItems[0]
    val relevance = firstItem.relevance!!
    assertEquals("1", relevance[elementFactorName("from_user_data")])
    assertEquals("1", relevance[elementFactorName("can_use_context_feature")])
  }

  private fun doTest(checkResults: (CompletionStartedEvent) -> Unit): Unit = onEdt {
    ContextFeatureProvider.EP_NAME.addExplicitExtension(JavaLanguage.INSTANCE, TestContextFeatureProvider(), testRootDisposable)
    ElementFeatureProvider.EP_NAME.addExplicitExtension(JavaLanguage.INSTANCE, TestElementFeatureProvider(), testRootDisposable)
    myFixture.completeBasic()
    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    val startedEvent = trackedEvents.first() as CompletionStartedEvent
    checkResults(startedEvent)
  }


  private class TestContextFeatureProvider() : ContextFeatureProvider {
    companion object {
      val USER_DATA_KEY = Key.create<Int>("TEST_KEY")
      const val USER_DATA_TEST_VALUE = 42
    }

    override fun getName(): String = "test"

    override fun calculateFeatures(environment: CompletionEnvironment): Map<String, MLFeatureValue> {
      environment.putUserData(USER_DATA_KEY, USER_DATA_TEST_VALUE)
      return mapOf("binary" to MLFeatureValue.binary(true),
                   "float" to MLFeatureValue.numerical(1),
                   "categorical" to MLFeatureValue.categorical(Category.VALUE1),
                   "classSimpleName" to MLFeatureValue.className(TestContextFeatureProvider::class.java, useSimpleName = true),
                   "classFullName" to MLFeatureValue.className(TestContextFeatureProvider::class.java, useSimpleName = false))
    }
  }

  private enum class Category {
    VALUE1, VALUE2
  }

  private class TestElementFeatureProvider() : ElementFeatureProvider {
    override fun getName(): String = "test"

    override fun calculateFeatures(element: LookupElement,
                                   location: CompletionLocation,
                                   contextFeatures: ContextFeatures): Map<String, MLFeatureValue> {
      val expectedValue = contextFeatures.getUserData(
        TestContextFeatureProvider.USER_DATA_KEY) == TestContextFeatureProvider.USER_DATA_TEST_VALUE
      return mapOf("binary" to MLFeatureValue.binary(false),
                   "float" to MLFeatureValue.numerical(2),
                   "categorical" to MLFeatureValue.categorical(Category.VALUE2),
                   "from_user_data" to MLFeatureValue.binary(expectedValue),
                   "can_use_context_feature" to MLFeatureValue.binary(contextFeatures.binaryValue(contextFactorName("binary")) ?: false)
      )
    }
  }

  companion object {
    private fun contextFactorName(name: String) = "ml_ctx_test_$name"

    private fun elementFactorName(name: String) = "ml_test_$name"
  }
}