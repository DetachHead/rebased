// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.intellij.diff.util.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Unit tests for [ReviewCommentsJsonExporter]. Exercises both the pure serialization logic
 * ([ReviewCommentsJsonExporter.toJson], no filesystem access) and the actual file write (via
 * a real [TemporaryFolder], standing in for a repo root) since [ReviewCommentsJsonExporter] has
 * no dependency on the platform.
 */
class ReviewCommentsJsonExporterTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `empty store exports an empty JSON array`() {
    val json = ReviewCommentsJsonExporter.toJson(emptyList())

    val parsed = Gson().fromJson(json, JsonArray::class.java)
    assertEquals(0, parsed.size())
  }

  @Test
  fun `multiple comments across multiple files export in insertion order with correct shape`() {
    // ReviewComment.line is 0-based internally; the exported JSON's "line" is 1-based (see
    // ReviewCommentsJsonExporter's class doc), so these are expected as line + 1 below.
    val comments = listOf(
      ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "first"),
      ReviewComment("b/c.txt", line = 42, side = Side.RIGHT, text = "second"),
    )

    val json = ReviewCommentsJsonExporter.toJson(comments)
    val parsed = Gson().fromJson(json, JsonArray::class.java)

    assertEquals(2, parsed.size())
    val first = parsed[0].asJsonObject
    assertEquals("a.txt", first["filePath"].asString)
    assertEquals(2, first["line"].asInt)
    assertEquals("LEFT", first["side"].asString)
    assertEquals("first", first["text"].asString)

    val second = parsed[1].asJsonObject
    assertEquals("b/c.txt", second["filePath"].asString)
    assertEquals(43, second["line"].asInt)
    assertEquals("RIGHT", second["side"].asString)
    assertEquals("second", second["text"].asString)
  }

  @Test
  fun `line 0 (first document line) exports as 1, not 0`() {
    val json = ReviewCommentsJsonExporter.toJson(listOf(ReviewComment("a.txt", line = 0, side = Side.RIGHT, text = "top of file")))
    val parsed = Gson().fromJson(json, JsonArray::class.java)

    assertEquals(1, parsed[0].asJsonObject["line"].asInt)
  }

  @Test
  fun `special characters in comment text escape correctly`() {
    val comment = ReviewComment(
      "a.txt",
      line = 1,
      side = Side.RIGHT,
      text = "quote \" backslash \\ newline \n tab \t unicode é",
    )

    val json = ReviewCommentsJsonExporter.toJson(listOf(comment))
    val parsed = Gson().fromJson(json, JsonArray::class.java)

    assertEquals(comment.text, parsed[0].asJsonObject["text"].asString)
  }

  @Test
  fun `export writes to repo-root slash dot-git slash review-comments dot json`() {
    val repoRoot = tempFolder.newFolder("repo")
    File(repoRoot, ".git").mkdirs()
    val comment = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "hi")

    val target = ReviewCommentsJsonExporter.export(listOf(comment), repoRoot)

    assertEquals(File(repoRoot, ".git/review-comments.json"), target)
    assertTrue(target.isFile)
    val parsed = Gson().fromJson(target.readText(), JsonArray::class.java)
    assertEquals(1, parsed.size())
    assertEquals("a.txt", parsed[0].asJsonObject["filePath"].asString)
  }

  @Test
  fun `export creates the dot-git directory if missing`() {
    val repoRoot = tempFolder.newFolder("bare-repo")

    val target = ReviewCommentsJsonExporter.export(emptyList(), repoRoot)

    assertTrue(target.isFile)
    assertEquals("[]", Gson().fromJson(target.readText(), JsonArray::class.java).toString())
  }

  @Test
  fun `export overwrites previous contents`() {
    val repoRoot = tempFolder.newFolder("repo2")
    val first = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "old")
    val second = ReviewComment("b.txt", line = 2, side = Side.RIGHT, text = "new")

    ReviewCommentsJsonExporter.export(listOf(first), repoRoot)
    val target = ReviewCommentsJsonExporter.export(listOf(second), repoRoot)

    val parsed = Gson().fromJson(target.readText(), JsonArray::class.java)
    assertEquals(1, parsed.size())
    assertEquals("b.txt", parsed[0].asJsonObject["filePath"].asString)
  }

  @Test
  fun `export falls back to a repo-root dotfile when dot-git is a file, not a directory`() {
    // Simulates a git worktree/submodule checkout, where .git is a plain file containing a
    // "gitdir: <path>" pointer rather than a directory.
    val repoRoot = tempFolder.newFolder("worktree-repo")
    File(repoRoot, ".git").writeText("gitdir: /somewhere/else/.git/worktrees/worktree-repo\n")
    val comment = ReviewComment("a.txt", line = 1, side = Side.LEFT, text = "hi")

    val target = ReviewCommentsJsonExporter.export(listOf(comment), repoRoot)

    assertEquals(File(repoRoot, ".git-review-comments.json"), target)
    assertTrue(target.isFile)
    val parsed = Gson().fromJson(target.readText(), JsonArray::class.java)
    assertEquals(1, parsed.size())
    assertEquals("a.txt", parsed[0].asJsonObject["filePath"].asString)
    // Must not have touched the .git pointer file itself.
    assertEquals("gitdir: /somewhere/else/.git/worktrees/worktree-repo\n", File(repoRoot, ".git").readText())
  }

  @Test
  fun `write failure surfaces an IOException rather than silently no-op`() {
    // Point "repoRoot" itself at a plain file, not a directory -- File(repoRoot, ".git") can
    // then never be created, so the write must fail loudly instead of pretending to succeed.
    val notADirectory = tempFolder.newFile("not-a-directory")

    assertThrows(IOException::class.java) {
      ReviewCommentsJsonExporter.export(emptyList(), notADirectory)
    }
    assertFalse(File(notADirectory, ".git/review-comments.json").exists())
  }

  @Test
  fun `clear deletes a previously exported file`() {
    val repoRoot = tempFolder.newFolder("repo3")
    File(repoRoot, ".git").mkdirs()
    ReviewCommentsJsonExporter.export(listOf(ReviewComment("a.txt", line = 0, side = Side.LEFT, text = "stale")), repoRoot)
    assertTrue(File(repoRoot, ".git/review-comments.json").isFile)

    ReviewCommentsJsonExporter.clear(repoRoot)

    assertFalse(File(repoRoot, ".git/review-comments.json").exists())
  }

  @Test
  fun `clear deletes the worktree-fallback file when dot-git is a file`() {
    val repoRoot = tempFolder.newFolder("worktree-repo2")
    File(repoRoot, ".git").writeText("gitdir: /somewhere/else\n")
    ReviewCommentsJsonExporter.export(listOf(ReviewComment("a.txt", line = 0, side = Side.LEFT, text = "stale")), repoRoot)
    assertTrue(File(repoRoot, ".git-review-comments.json").isFile)

    ReviewCommentsJsonExporter.clear(repoRoot)

    assertFalse(File(repoRoot, ".git-review-comments.json").exists())
  }

  @Test
  fun `clear on a repo root with no prior export is a no-op, not a failure`() {
    val repoRoot = tempFolder.newFolder("repo4")
    File(repoRoot, ".git").mkdirs()

    ReviewCommentsJsonExporter.clear(repoRoot) // must not throw
  }
}
