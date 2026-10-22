// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.filePrediction.features.history.context

import com.intellij.filePrediction.FilePredictionTestDataHelper
import com.intellij.filePrediction.FilePredictionTestProjectBuilder
import com.intellij.filePrediction.features.ConstFileFeaturesProducer
import com.intellij.filePrediction.features.FileFeaturesProducer
import com.intellij.filePrediction.features.FilePredictionFeature
import com.intellij.filePrediction.features.FilePredictionFeature.Companion.binary
import com.intellij.filePrediction.features.FilePredictionFeaturesCache
import com.intellij.filePrediction.features.history.FilePredictionHistoryBaseTest
import com.intellij.filePrediction.features.history.FilePredictionNGramFeatures
import com.intellij.filePrediction.references.ExternalReferencesResult.Companion.FAILED_COMPUTATION
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class FilePredictionContextFeaturesTest : FilePredictionHistoryBaseTest() {
  private val projectFixture = codeInsightProjectFixture()
  private val myFixture by codeInsightFixture(projectFixture)

  private fun doTest(builder: FilePredictionTestProjectBuilder, vararg expected: Pair<String, FilePredictionFeature>) {
    doTestContextFeatures(builder, ConstFileFeaturesProducer(*expected))
  }

  private fun doTestContextFeatures(builder: FilePredictionTestProjectBuilder, featuresProvider: FileFeaturesProducer): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val root = builder.create(myFixture)
      assertNotNull(root, "Cannot create test project")

      val file = FilePredictionTestDataHelper.findMainTestFile(root)
      assertNotNull(file, "Cannot find main project file")

      val manager = FileEditorManager.getInstance(myFixture.project)

      val prevFile = manager.selectedEditor?.file
      assertTrue(prevFile != file, "Cannot open main file because it's already opened")

      val provider = FilePredictionContextFeatures()
      val emptyCache = FilePredictionFeaturesCache(FAILED_COMPUTATION, FilePredictionNGramFeatures(emptyMap()))
      val actual = provider.calculateFileFeatures(myFixture.project, file!!, prevFile, emptyCache)
      val expected = featuresProvider.produce(myFixture.project)
      for (feature in expected.entries) {
        assertTrue(actual.containsKey(feature.key), "Cannot find feature '${feature.key}' in $actual")
        assertEquals(feature.value, actual[feature.key], "The value of feature '${feature.key}' is different from expected")
      }
    }
  }

  @Test
  fun `test no opened files`() {
    val builder = FilePredictionTestProjectBuilder("com")
    doTest(
      builder,
      "opened" to binary(false)
    )
  }

  @Test
  fun `test single opened file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .open("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(false)
    )
  }

  @Test
  fun `test opened main file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain()
      .open("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(true)
    )
  }

  @Test
  fun `test several opened files`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .open("com/test/Foo.txt")
      .open("com/test/Bar.txt")
    doTest(
      builder,
      "opened" to binary(false)
    )
  }

  @Test
  fun `test main and several other opened files`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .open("com/test/Foo.txt")
      .openMain()
      .open("com/test/Bar.txt")
    doTest(
      builder,
      "opened" to binary(true)
    )
  }

  @Test
  fun `test closed file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain()
      .closeMain()
    doTest(
      builder,
      "opened" to binary(false)
    )
  }

  @Test
  fun `test re-opened file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain().closeMain().openMain()
      .open("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(true)
    )
  }

  @Test
  fun `test select already opened file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain()
      .open("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(true)
    )
  }

  @Test
  fun `test switching between opened file`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain()
      .open("com/test/Foo.txt")
      .selectMain()
      .select("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(true)
    )
  }

  @Test
  fun `test switching between opened file without main`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .open("com/test/Bar.txt")
      .open("com/test/Foo.txt")
      .select("com/test/Bar.txt")
      .select("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(false)
    )
  }

  @Test
  fun `test switching between opened file with closed main`() {
    val builder = FilePredictionTestProjectBuilder("com")
      .openMain()
      .open("com/test/Foo.txt")
      .selectMain().closeMain()
      .open("com/test/Bar.txt")
      .select("com/test/Foo.txt")
    doTest(
      builder,
      "opened" to binary(false)
    )
  }
}