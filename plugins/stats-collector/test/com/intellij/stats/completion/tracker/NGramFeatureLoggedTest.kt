// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion.tracker

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.stats.completion.events.CompletionStartedEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NGramFeatureLoggedTest : CompletionLoggingTestBase() {
  @Test
  fun `test ngram is in logs`(): Unit = onEdt {
    myFixture.configureByText(JavaFileType.INSTANCE, "class T { void r() { } public static void main(String[] args) { new T().<caret> } }")
    myFixture.completeBasic()
    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    val startedEvent = trackedEvents.first() as CompletionStartedEvent
    assertTrue(startedEvent.newCompletionListItems.isNotEmpty())
    assertTrue(startedEvent.newCompletionListItems.any { it.relevance?.contains("ml_ngram_file") ?: false })
  }
}