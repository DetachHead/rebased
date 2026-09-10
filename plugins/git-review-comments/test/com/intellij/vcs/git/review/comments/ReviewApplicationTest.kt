// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain unit tests for [ReviewApplication.parseRef]'s argument parsing, kept separate from
 * the actual diff-opening side effect in [ReviewApplication.executeCommand] (which needs a
 * running platform/EDT and is out of scope for a plain unit test).
 *
 * `args[0]` is always the command name itself (`"review"`), matching how
 * `ApplicationStarterBase`/`DiffApplication.executeCommand` receive `args` -- see
 * `platform/diff-impl/src/com/intellij/diff/applications/DiffApplication.kt`'s own
 * `args.drop(1)`.
 */
class ReviewApplicationTest {
  @Test
  fun `no ref argument means uncommitted changes`() {
    assertNull(ReviewApplication.parseRef(listOf("review")))
  }

  @Test
  fun `a single ref argument is returned as-is`() {
    assertEquals("HEAD~1", ReviewApplication.parseRef(listOf("review", "HEAD~1")))
  }

  @Test
  fun `a range argument is returned as one string, unsplit`() {
    assertEquals("main..feature", ReviewApplication.parseRef(listOf("review", "main..feature")))
  }
}
