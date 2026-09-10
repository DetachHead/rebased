// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.collaboration.ui.codereview.diff.DiffLineLocation
import com.intellij.diff.util.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test for [InMemoryReviewEditorModel]'s construction logic, standing in for a
 * full [ReviewDiffExtension.onViewerCreated] viewer-creation integration test - reaching into a
 * real [com.intellij.diff.tools.simple.SimpleDiffViewer]/`UnifiedDiffViewer` requires a full
 * platform light-test fixture plus building an actual `DiffRequestChain`, which only becomes
 * available once `ChangesetResolver`/`ReviewApplication` exist (a later task); see the class doc
 * on [ReviewDiffExtension] for the corresponding scope note on `isLineCommentable`.
 *
 * Exercises the model directly against fake `locationToLine`/`lineToLocation` mappings, the
 * same function shapes `showCodeReview` hands to a [ReviewDiffExtension] (see
 * `com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview`).
 *
 * [InMemoryReviewEditorModel.gutterControlsState] is a `StateFlow` built via `.stateIn(cs,
 * SharingStarted.Eagerly, null)`; each test scope uses [Dispatchers.Unconfined] so that eager
 * collection runs synchronously to completion before the constructor call returns, without
 * needing a `kotlinx-coroutines-test` dependency.
 */
class InMemoryReviewEditorModelTest {
  private val scopes = mutableListOf<CoroutineScope>()

  private fun testScope(): CoroutineScope =
    CoroutineScope(Dispatchers.Unconfined + Job()).also { scopes += it }

  @After
  fun tearDown() {
    scopes.forEach { it.cancel() }
    scopes.clear()
  }

  /** A single-file diff, right side only, lines 0..4 map to themselves. */
  private fun identityMappings(): Pair<(DiffLineLocation) -> Int?, (Int) -> DiffLineLocation?> {
    val locationToLine: (DiffLineLocation) -> Int? = { (side, line) -> line.takeIf { side == Side.RIGHT && it in 0..4 } }
    val lineToLocation: (Int) -> DiffLineLocation? = { line -> (Side.RIGHT to line).takeIf { line in 0..4 } }
    return locationToLine to lineToLocation
  }

  @Test
  fun `isLineCommentable reflects lineToLocation for lines within the diff`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val model = InMemoryReviewEditorModel(testScope(), InMemoryReviewCommentStore(), "a.txt", locationToLine, lineToLocation)

    val state = model.gutterControlsState.value
    checkNotNull(state)
    assertTrue(state.isLineCommentable(0))
    assertTrue(state.isLineCommentable(4))
    assertFalse(state.isLineCommentable(5))
  }

  @Test
  fun `gutterControlsState reflects only comments on this model's filePath`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    store.addComment(ReviewComment("a.txt", line = 2, side = Side.RIGHT, text = "on this file"))
    store.addComment(ReviewComment("b.txt", line = 3, side = Side.RIGHT, text = "on a different file"))

    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    val state = model.gutterControlsState.value
    checkNotNull(state)
    assertEquals(setOf(2), state.linesWithComments)
  }

  @Test
  fun `gutterControlsState updates live as comments are added to the store`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    assertEquals(emptySet<Int>(), model.gutterControlsState.value?.linesWithComments)

    store.addComment(ReviewComment("a.txt", line = 1, side = Side.RIGHT, text = "new"))

    assertEquals(setOf(1), model.gutterControlsState.value?.linesWithComments)
  }

  @Test
  fun `requestNewComment adds a comment with the text captured from requestCommentText`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation) { "actual typed text" }

    model.requestNewComment(3)

    assertEquals(
      listOf(ReviewComment("a.txt", line = 3, side = Side.RIGHT, text = "actual typed text")),
      store.commentsForFile("a.txt"),
    )
  }

  @Test
  fun `requestNewComment for an unmapped line is a no-op`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation) { "typed text" }

    model.requestNewComment(99)

    assertTrue(store.isEmpty())
  }

  @Test
  fun `requestNewComment does nothing when the text callback returns null -- user cancelled`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation) { null }

    model.requestNewComment(3)

    assertTrue(store.isEmpty())
  }

  @Test
  fun `requestNewComment does nothing when the text callback returns blank text`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation) { "   " }

    model.requestNewComment(3)

    assertTrue(store.isEmpty())
  }

  @Test
  fun `cancelNewComment removes an empty-text placeholder comment at the mapped location`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    store.addComment(ReviewComment("a.txt", line = 3, side = Side.RIGHT, text = ""))
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    model.cancelNewComment(3)

    assertEquals(emptyList<ReviewComment>(), store.commentsForFile("a.txt"))
  }

  @Test
  fun `cancelNewComment does not remove a real, already-written comment at the same location`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val realComment = ReviewComment("a.txt", line = 3, side = Side.RIGHT, text = "a real comment")
    store.addComment(realComment)
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    model.cancelNewComment(3)

    assertEquals(listOf(realComment), store.commentsForFile("a.txt"))
  }

  @Test
  fun `canCreateComment default delegates to gutterControlsState isLineCommentable`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val model = InMemoryReviewEditorModel(testScope(), InMemoryReviewCommentStore(), "a.txt", locationToLine, lineToLocation)

    assertTrue(model.canCreateComment(0))
    assertFalse(model.canCreateComment(5))
  }

  @Test
  fun `inlays reflects only non-empty comments on this model's filePath, mapped to their line`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    store.addComment(ReviewComment("a.txt", line = 2, side = Side.RIGHT, text = "on this file"))
    store.addComment(ReviewComment("b.txt", line = 3, side = Side.RIGHT, text = "on a different file"))
    store.addComment(ReviewComment("a.txt", line = 1, side = Side.RIGHT, text = ""))

    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    val inlays = model.inlays.value
    assertEquals(1, inlays.size)
    val inlay = inlays.single()
    assertEquals("on this file", inlay.text)
    assertEquals(2, inlay.line.value)
    assertTrue(inlay.isVisible.value)
  }

  @Test
  fun `inlays updates live as comments are added to the store`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    assertTrue(model.inlays.value.isEmpty())

    store.addComment(ReviewComment("a.txt", line = 1, side = Side.RIGHT, text = "new comment"))

    assertEquals(listOf("new comment"), model.inlays.value.map { it.text })
  }

  @Test
  fun `inlays excludes a comment whose location no longer maps to a line`() {
    val (locationToLine, lineToLocation) = identityMappings()
    val store = InMemoryReviewCommentStore()
    store.addComment(ReviewComment("a.txt", line = 99, side = Side.RIGHT, text = "unmapped"))

    val model = InMemoryReviewEditorModel(testScope(), store, "a.txt", locationToLine, lineToLocation)

    assertTrue(model.inlays.value.isEmpty())
  }
}
