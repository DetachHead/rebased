// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Plain unit tests for [ChangesetResolver] against a fake [GitCommandRunner] -- no real git
 * checkout, working directory, or platform fixture required. Also exercises the pure
 * ref-parsing companion functions ([ChangesetResolver.buildDiffNameOnlyArgs],
 * [ChangesetResolver.parseNameOnlyOutput], [ChangesetResolver.resolveSides]) directly.
 */
class ChangesetResolverTest {
  // ---- buildDiffNameOnlyArgs ----

  @Test
  fun `buildDiffNameOnlyArgs with no ref diffs HEAD`() {
    assertEquals(listOf("diff", "--name-only", "HEAD"), ChangesetResolver.buildDiffNameOnlyArgs(null))
  }

  @Test
  fun `buildDiffNameOnlyArgs with a single ref diffs that ref`() {
    assertEquals(listOf("diff", "--name-only", "HEAD~1"), ChangesetResolver.buildDiffNameOnlyArgs("HEAD~1"))
  }

  @Test
  fun `buildDiffNameOnlyArgs with a two-dot range splits into two positional refs`() {
    assertEquals(listOf("diff", "--name-only", "main", "feature"), ChangesetResolver.buildDiffNameOnlyArgs("main..feature"))
  }

  @Test
  fun `buildDiffNameOnlyArgs with a three-dot range splits into two positional refs`() {
    assertEquals(listOf("diff", "--name-only", "main", "feature"), ChangesetResolver.buildDiffNameOnlyArgs("main...feature"))
  }

  @Test
  fun `buildDiffNameOnlyArgs with two space-separated refs passes both through`() {
    assertEquals(listOf("diff", "--name-only", "main", "feature"), ChangesetResolver.buildDiffNameOnlyArgs("main feature"))
  }

  // ---- parseNameOnlyOutput ----

  @Test
  fun `parseNameOnlyOutput on empty output returns an empty list`() {
    assertEquals(emptyList<String>(), ChangesetResolver.parseNameOnlyOutput(""))
    assertEquals(emptyList<String>(), ChangesetResolver.parseNameOnlyOutput("\n\n"))
  }

  @Test
  fun `parseNameOnlyOutput splits and trims lines, dropping blanks`() {
    assertEquals(
      listOf("a.txt", "dir/b.kt"),
      ChangesetResolver.parseNameOnlyOutput("a.txt\n\ndir/b.kt\n"),
    )
  }

  // ---- resolveSides ----

  @Test
  fun `resolveSides with no ref is HEAD versus working tree`() {
    assertEquals("HEAD" to null, ChangesetResolver.resolveSides(null))
  }

  @Test
  fun `resolveSides with a single ref is that ref versus working tree`() {
    assertEquals("HEAD~1" to null, ChangesetResolver.resolveSides("HEAD~1"))
  }

  @Test
  fun `resolveSides with a range is ref versus ref`() {
    assertEquals("main" to "feature", ChangesetResolver.resolveSides("main..feature"))
    assertEquals("main" to "feature", ChangesetResolver.resolveSides("main feature"))
  }

  // ---- resolveChangedFiles (end-to-end against a fake runner) ----

  private class FakeGitCommandRunner(private val responses: Map<List<String>, String>) : GitCommandRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(vararg args: String): String {
      val argsList = args.toList()
      calls += argsList
      return responses[argsList] ?: throw GitCommandException("unknown revision or path not in the working tree: $argsList")
    }
  }

  @Test
  fun `uncommitted changes reads new content off disk and old content via git show HEAD`() {
    val repoRoot = createTempDir()
    try {
      File(repoRoot, "a.txt").writeText("new on disk")
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "HEAD") to "a.txt\n",
          listOf("show", "HEAD:a.txt") to "old from HEAD",
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = null)

      assertEquals(listOf(ChangedFile("a.txt", oldContent = "old from HEAD", newContent = "new on disk")), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `ref versus working tree reads old content via git show ref and new content off disk`() {
    val repoRoot = createTempDir()
    try {
      File(repoRoot, "a.txt").writeText("working tree content")
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "HEAD~1") to "a.txt\n",
          listOf("show", "HEAD~1:a.txt") to "content at HEAD~1",
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "HEAD~1")

      assertEquals(listOf(ChangedFile("a.txt", oldContent = "content at HEAD~1", newContent = "working tree content")), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `ref versus ref reads both sides via git show, ignoring the working tree`() {
    val repoRoot = createTempDir()
    try {
      // Deliberately different from either git-show response, to prove the working tree is not consulted.
      File(repoRoot, "a.txt").writeText("uncommitted local edit")
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "main", "feature") to "a.txt\n",
          listOf("show", "main:a.txt") to "content on main",
          listOf("show", "feature:a.txt") to "content on feature",
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "main..feature")

      assertEquals(listOf(ChangedFile("a.txt", oldContent = "content on main", newContent = "content on feature")), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `empty changeset resolves to an empty list`() {
    val repoRoot = createTempDir()
    try {
      val git = FakeGitCommandRunner(mapOf(listOf("diff", "--name-only", "HEAD") to ""))

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = null)

      assertTrue(changed.isEmpty())
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `an added file has null old content`() {
    val repoRoot = createTempDir()
    try {
      File(repoRoot, "new.txt").writeText("brand new")
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "HEAD") to "new.txt\n",
          // No "show HEAD:new.txt" entry -- the fake throws GitCommandException for it,
          // simulating git's "path does not exist" error, which ChangesetResolver must
          // treat as "no old content" rather than propagating.
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = null)

      assertEquals(listOf(ChangedFile("new.txt", oldContent = null, newContent = "brand new")), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `a deleted file has null new content`() {
    val repoRoot = createTempDir()
    try {
      // Not creating gone.txt on disk at all -- simulates a deletion.
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "HEAD") to "gone.txt\n",
          listOf("show", "HEAD:gone.txt") to "content that used to be there",
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = null)

      assertEquals(listOf(ChangedFile("gone.txt", oldContent = "content that used to be there", newContent = null)), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `a nonexistent ref propagates a GitCommandException`() {
    val repoRoot = createTempDir()
    try {
      val git = FakeGitCommandRunner(emptyMap())

      try {
        ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "no-such-ref")
        fail("expected a GitCommandException")
      }
      catch (e: GitCommandException) {
        assertTrue(e.message!!.contains("no-such-ref"))
      }
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  private fun createTempDir(): File = kotlin.io.path.createTempDirectory("changeset-resolver-test").toFile()
}
