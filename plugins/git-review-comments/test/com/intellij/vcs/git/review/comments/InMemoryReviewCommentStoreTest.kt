// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.util.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain unit tests for [InMemoryReviewCommentStore] - pure Kotlin logic over a [kotlinx.coroutines.flow.MutableStateFlow],
 * no platform fixture required (see the class doc on [InMemoryReviewCommentStore] for why it
 * has no dependency on the platform).
 */
class InMemoryReviewCommentStoreTest {
  @Test
  fun `empty store has no comments and is empty`() {
    val store = InMemoryReviewCommentStore()

    assertTrue(store.isEmpty())
    assertEquals(emptyList<ReviewComment>(), store.comments.value)
    assertEquals(emptyList<ReviewComment>(), store.commentsForFile("a.txt"))
  }

  @Test
  fun `addComment appends to the store and is reflected in comments and commentsForFile`() {
    val store = InMemoryReviewCommentStore()
    val comment = ReviewComment("a.txt", line = 3, side = Side.RIGHT, text = "looks off")

    store.addComment(comment)

    assertTrue(!store.isEmpty())
    assertEquals(listOf(comment), store.comments.value)
    assertEquals(listOf(comment), store.commentsForFile("a.txt"))
  }

  @Test
  fun `commentsForFile filters by file path`() {
    val store = InMemoryReviewCommentStore()
    val commentA = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "a")
    val commentB = ReviewComment("b.txt", line = 2, side = Side.RIGHT, text = "b")

    store.addComment(commentA)
    store.addComment(commentB)

    assertEquals(listOf(commentA), store.commentsForFile("a.txt"))
    assertEquals(listOf(commentB), store.commentsForFile("b.txt"))
    assertEquals(emptyList<ReviewComment>(), store.commentsForFile("c.txt"))
    assertEquals(2, store.comments.value.size)
  }

  @Test
  fun `duplicate comments on the same file and line are all retained`() {
    val store = InMemoryReviewCommentStore()
    val comment = ReviewComment("a.txt", line = 5, side = Side.RIGHT, text = "same everything")

    store.addComment(comment)
    store.addComment(comment)

    assertEquals(listOf(comment, comment), store.comments.value)
    assertEquals(listOf(comment, comment), store.commentsForFile("a.txt"))
  }

  @Test
  fun `removeComment removes only the first matching occurrence`() {
    val store = InMemoryReviewCommentStore()
    val comment = ReviewComment("a.txt", line = 5, side = Side.RIGHT, text = "dup")
    val other = ReviewComment("a.txt", line = 6, side = Side.RIGHT, text = "unique")

    store.addComment(comment)
    store.addComment(other)
    store.addComment(comment)

    store.removeComment(comment)

    assertEquals(listOf(other, comment), store.comments.value)
  }

  @Test
  fun `removeComment on an empty store is a no-op`() {
    val store = InMemoryReviewCommentStore()
    val comment = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "x")

    store.removeComment(comment)

    assertTrue(store.isEmpty())
  }

  @Test
  fun `removeComment for a comment not present is a no-op`() {
    val store = InMemoryReviewCommentStore()
    val present = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "present")
    val absent = ReviewComment("a.txt", line = 2, side = Side.LEFT, text = "absent")
    store.addComment(present)

    store.removeComment(absent)

    assertEquals(listOf(present), store.comments.value)
  }

  @Test
  fun `removeCommentsAt removes every comment matching file, line and side`() {
    val store = InMemoryReviewCommentStore()
    val match1 = ReviewComment("a.txt", line = 4, side = Side.RIGHT, text = "one")
    val match2 = ReviewComment("a.txt", line = 4, side = Side.RIGHT, text = "two")
    val differentLine = ReviewComment("a.txt", line = 5, side = Side.RIGHT, text = "keep-line")
    val differentSide = ReviewComment("a.txt", line = 4, side = Side.LEFT, text = "keep-side")
    val differentFile = ReviewComment("b.txt", line = 4, side = Side.RIGHT, text = "keep-file")

    store.addComment(match1)
    store.addComment(match2)
    store.addComment(differentLine)
    store.addComment(differentSide)
    store.addComment(differentFile)

    store.removeCommentsAt("a.txt", line = 4, side = Side.RIGHT)

    val remaining = store.comments.value
    assertEquals(3, remaining.size)
    assertTrue(remaining.containsAll(listOf(differentLine, differentSide, differentFile)))
    assertTrue(remaining.none { it == match1 || it == match2 })
  }

  @Test
  fun `removeCommentsAt on an empty store is a no-op`() {
    val store = InMemoryReviewCommentStore()

    store.removeCommentsAt("a.txt", line = 1, side = Side.LEFT)

    assertTrue(store.isEmpty())
  }

  @Test
  fun `clear removes every comment across every file`() {
    val store = InMemoryReviewCommentStore()
    store.addComment(ReviewComment("a.txt", line = 1, side = Side.RIGHT, text = "one"))
    store.addComment(ReviewComment("b.txt", line = 2, side = Side.LEFT, text = "two"))

    store.clear()

    assertTrue(store.isEmpty())
    assertEquals(emptyList<ReviewComment>(), store.comments.value)
  }

  @Test
  fun `clear on an empty store is a no-op`() {
    val store = InMemoryReviewCommentStore()

    store.clear()

    assertTrue(store.isEmpty())
  }
}
