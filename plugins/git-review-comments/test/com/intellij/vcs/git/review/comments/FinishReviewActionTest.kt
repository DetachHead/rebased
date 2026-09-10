// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.DiffContext
import com.intellij.openapi.project.Project
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FinishReviewAction.isEnabled] -- the pure enablement logic backing
 * [FinishReviewAction.update]. Exercised against a bare-bones fake [DiffContext] (a plain
 * abstract class with no platform dependency beyond `intellij.platform.diff`, already a module
 * dependency here) rather than a real [com.intellij.openapi.actionSystem.AnActionEvent], which
 * would need a full platform/`ActionManager` fixture.
 */
class FinishReviewActionTest {
  private class FakeDiffContext : DiffContext() {
    override fun getProject(): Project? = null
    override fun isWindowFocused(): Boolean = true
    override fun isFocusedInWindow(): Boolean = true
    override fun requestFocusInWindow(): Unit = Unit
  }

  @Test
  fun `no diff context means no active review session, so disabled`() {
    assertFalse(FinishReviewAction.isEnabled(null))
  }

  @Test
  fun `diff context without a comment store means no active review session, so disabled`() {
    val context = FakeDiffContext()

    assertFalse(FinishReviewAction.isEnabled(context))
  }

  @Test
  fun `diff context with a comment store attached is an active review session, so enabled`() {
    val context = FakeDiffContext()
    context.putUserData(InMemoryReviewCommentStore.KEY, InMemoryReviewCommentStore())

    assertTrue(FinishReviewAction.isEnabled(context))
  }

  @Test
  fun `enabled even when the attached comment store is empty -- finishing with zero comments is valid`() {
    val context = FakeDiffContext()
    val emptyStore = InMemoryReviewCommentStore()
    assertTrue(emptyStore.isEmpty())
    context.putUserData(InMemoryReviewCommentStore.KEY, emptyStore)

    assertTrue(FinishReviewAction.isEnabled(context))
  }
}
