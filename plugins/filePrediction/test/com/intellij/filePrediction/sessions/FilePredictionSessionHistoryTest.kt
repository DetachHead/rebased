// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.filePrediction.sessions

import com.intellij.filePrediction.FilePredictionSessionHistory
import com.intellij.filePrediction.FilePredictionTestDataHelper
import com.intellij.filePrediction.FilePredictionTestProjectBuilder
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource.NEIGHBOR
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource.OPEN
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource.RECENT
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource.REFERENCE
import com.intellij.filePrediction.candidates.FilePredictionCandidateSource.VCS
import com.intellij.filePrediction.predictor.FilePredictionCompressedCandidate
import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class FilePredictionSessionHistoryTest {
  private val projectFixture = codeInsightProjectFixture()
  private val myFixture by codeInsightFixture(projectFixture)

  private fun doTestSession(previous: List<Pair<String, FilePredictionCandidateSource>>,
                            expected: Set<String>, candidatesToSelect: Int? = null) {
    doTest(listOf(TestFilePredictionSession(previous)), expected, candidatesToSelect)
  }

  private fun doTestTwoSessions(previous1: List<Pair<String, FilePredictionCandidateSource>>,
                                previous2: List<Pair<String, FilePredictionCandidateSource>>,
                                expected: Set<String>, candidatesToSelect: Int? = null) {
    val sessions = listOf(
      TestFilePredictionSession(previous1),
      TestFilePredictionSession(previous2)
    )
    doTest(sessions, expected, candidatesToSelect)
  }

  private fun doTestThreeSessions(previous1: List<Pair<String, FilePredictionCandidateSource>>,
                                  previous2: List<Pair<String, FilePredictionCandidateSource>>,
                                  previous3: List<Pair<String, FilePredictionCandidateSource>>,
                                  expected: Set<String>, candidatesToSelect: Int? = null) {
    val sessions = listOf(
      TestFilePredictionSession(previous1),
      TestFilePredictionSession(previous2),
      TestFilePredictionSession(previous3)
    )
    doTest(sessions, expected, candidatesToSelect)
  }


  private fun doTest(sessions: List<TestFilePredictionSession>,
                     expected: Set<String>, candidatesToSelect: Int? = null): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val builder = FilePredictionTestProjectBuilder("com")
      for (session in sessions) {
        session.candidates.forEach {
          builder.addFileIfNeeded(it.first)
        }
      }

      val root = builder.create(myFixture)
      assertNotNull(root, "Cannot create test project")

      val file = FilePredictionTestDataHelper.findMainTestFile(root)
      assertNotNull(file, "Cannot find file with '${FilePredictionTestDataHelper.DEFAULT_MAIN_FILE}' name")

      val history = FilePredictionSessionHistory.getInstance(myFixture.project)
      history.setCandidatesPerSession(5)

      for (session in sessions) {
        val candidates = session.candidates.map { newCandidate(it.first, it.second) }.toList()
        history.onCandidatesCalculated(candidates)
      }

      val actual = history.selectCandidates(candidatesToSelect ?: 5)
      assertTrue(actual == expected, "Actual candidates are differ from expected")
    }
  }

  private fun newCandidate(file: String, source: FilePredictionCandidateSource): FilePredictionCompressedCandidate {
    return FilePredictionCompressedCandidate(file, source, "", 10, 5, 0.5)
  }

  @Test
  fun `test candidates are saved`() {
    doTestSession(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE
    ), hashSetOf(
      "com/subdir/test/Foo1.java",
      "com/subdir/test/Foo2.java",
      "com/test/Helper.java"
    ))
  }

  @Test
  fun `test only neighbor and reference candidates are saved`() {
    doTestSession(listOf(
      "com/subdir/test/Foo1.java" to OPEN,
      "com/subdir/test/Foo2.java" to VCS,
      "com/subdir/test/Foo3.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE,
      "com/test/Foo.txt" to RECENT
    ), hashSetOf(
      "com/subdir/test/Foo3.java",
      "com/test/Helper.java"
    ))
  }

  @Test
  fun `test top candidates are saved`() {
    doTestSession(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to REFERENCE,
      "com/subdir/test/Foo3.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE,
      "com/test/Foo1.txt" to REFERENCE,
      "com/test/Foo2.txt" to REFERENCE,
      "com/test/Foo3.txt" to REFERENCE
    ), hashSetOf(
      "com/subdir/test/Foo1.java",
      "com/subdir/test/Foo2.java",
      "com/subdir/test/Foo3.java",
      "com/test/Helper.java",
      "com/test/Foo1.txt"
    ))
  }

  @Test
  fun `test top candidates are returned`() {
    doTestSession(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to REFERENCE,
      "com/subdir/test/Foo3.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE,
      "com/test/Foo1.txt" to REFERENCE,
      "com/test/Foo2.txt" to REFERENCE,
      "com/test/Foo3.txt" to REFERENCE
    ), hashSetOf(
      "com/subdir/test/Foo1.java",
      "com/subdir/test/Foo2.java"
    ), 2)
  }

  @Test
  fun `test candidates from two last sessions are saved`() {
    doTestTwoSessions(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE
    ), listOf(
      "com/components/ui/Bar1.txt" to NEIGHBOR,
      "com/components/ui/Bar2.txt" to NEIGHBOR,
      "com/components/ui/Bar3.txt" to NEIGHBOR
    ), hashSetOf(
      "com/components/ui/Bar1.txt",
      "com/components/ui/Bar2.txt",
      "com/components/ui/Bar3.txt",
      "com/subdir/test/Foo1.java",
      "com/subdir/test/Foo2.java",
      "com/test/Helper.java"
    ))
  }

  @Test
  fun `test candidates from three last sessions are saved`() {
    doTestThreeSessions(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE
    ), listOf(
      "com/components/ui/Bar1.txt" to NEIGHBOR,
      "com/components/ui/Bar2.txt" to NEIGHBOR,
      "com/components/ui/Bar3.txt" to NEIGHBOR
    ), listOf(
      "com/controller/Baz1.java" to REFERENCE,
      "com/controller/Baz2.java" to NEIGHBOR,
      "com/controller/Baz3.java" to REFERENCE
    ), hashSetOf(
      "com/controller/Baz1.java",
      "com/controller/Baz2.java",
      "com/controller/Baz3.java",
      "com/components/ui/Bar1.txt",
      "com/components/ui/Bar2.txt",
      "com/components/ui/Bar3.txt",
      "com/subdir/test/Foo1.java",
      "com/subdir/test/Foo2.java",
      "com/test/Helper.java"
    ))
  }

  @Test
  fun `test candidates only from three last sessions are saved`() {
    doTest(listOf(TestFilePredictionSession(listOf(
      "com/subdir/test/Foo1.java" to NEIGHBOR,
      "com/subdir/test/Foo2.java" to NEIGHBOR,
      "com/test/Helper.java" to REFERENCE
    )), TestFilePredictionSession(listOf(
      "com/components/ui/Bar1.txt" to NEIGHBOR,
      "com/components/ui/Bar2.txt" to NEIGHBOR,
      "com/components/ui/Bar3.txt" to NEIGHBOR
    )), TestFilePredictionSession(listOf(
      "com/controller/Baz1.java" to REFERENCE,
      "com/controller/Baz2.java" to NEIGHBOR
    )), TestFilePredictionSession(listOf(
      "com/test/dir/TestFoo1.java" to REFERENCE,
      "com/test/dir/TestFoo2.java" to NEIGHBOR,
      "com/test/dir/TestFoo3.java" to REFERENCE
    ))), hashSetOf(
      "com/test/dir/TestFoo1.java",
      "com/test/dir/TestFoo2.java",
      "com/test/dir/TestFoo3.java",
      "com/controller/Baz1.java",
      "com/controller/Baz2.java",
      "com/components/ui/Bar1.txt",
      "com/components/ui/Bar2.txt",
      "com/components/ui/Bar3.txt"
    ))
  }
}

private class TestFilePredictionSession(val candidates: List<Pair<String, FilePredictionCandidateSource>>)