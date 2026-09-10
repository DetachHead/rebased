// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Plain unit tests for [ChangesetResolver] against a fake [GitCommandRunner] -- no real git
 * checkout, working directory, or platform fixture required. Also exercises the pure
 * ref-parsing companion functions ([ChangesetResolver.buildDiffNameOnlyArgs],
 * [ChangesetResolver.parseNameOnlyOutput], [ChangesetResolver.splitRef],
 * [ChangesetResolver.requireSafeRef], [ChangesetResolver.isPathNotFoundError]) directly.
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
  fun `buildDiffNameOnlyArgs with a three-dot range is passed through unsplit -- git's own merge-base syntax`() {
    // main...feature (git diff a...b) is a single, self-contained positional argument with
    // its own symmetric/merge-base-diff meaning -- splitting it into two positional refs
    // ("main" "feature") would silently change it into a plain two-ref diff instead.
    assertEquals(listOf("diff", "--name-only", "main...feature"), ChangesetResolver.buildDiffNameOnlyArgs("main...feature"))
  }

  @Test
  fun `buildDiffNameOnlyArgs with two space-separated refs passes both through`() {
    assertEquals(listOf("diff", "--name-only", "main", "feature"), ChangesetResolver.buildDiffNameOnlyArgs("main feature"))
  }

  @Test
  fun `buildDiffNameOnlyArgs rejects a ref that looks like a command-line option`() {
    try {
      ChangesetResolver.buildDiffNameOnlyArgs("--upload-pack=evil")
      fail("expected a GitCommandException")
    }
    catch (e: GitCommandException) {
      assertTrue(e.message!!.contains("--upload-pack=evil"))
    }
  }

  @Test
  fun `buildDiffNameOnlyArgs rejects either half of a range that looks like an option`() {
    try {
      ChangesetResolver.buildDiffNameOnlyArgs("main..-Xoption")
      fail("expected a GitCommandException")
    }
    catch (e: GitCommandException) {
      assertTrue(e.message!!.contains("-Xoption"))
    }
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

  // ---- splitRef ----

  @Test
  fun `splitRef with a plain ref returns just that ref`() {
    assertEquals(ChangesetResolver.SplitRef("HEAD~1", null), ChangesetResolver.splitRef("HEAD~1"))
  }

  @Test
  fun `splitRef with a two-dot range splits without merge-base semantics`() {
    assertEquals(ChangesetResolver.SplitRef("main", "feature", isMergeBaseRange = false), ChangesetResolver.splitRef("main..feature"))
  }

  @Test
  fun `splitRef with a three-dot range splits with merge-base semantics`() {
    assertEquals(ChangesetResolver.SplitRef("main", "feature", isMergeBaseRange = true), ChangesetResolver.splitRef("main...feature"))
  }

  @Test
  fun `splitRef with two space-separated refs splits without merge-base semantics`() {
    assertEquals(ChangesetResolver.SplitRef("main", "feature", isMergeBaseRange = false), ChangesetResolver.splitRef("main feature"))
  }

  // ---- requireSafeRef / isPathNotFoundError ----

  @Test
  fun `requireSafeRef rejects a ref starting with a dash`() {
    try {
      ChangesetResolver.requireSafeRef("--output=/tmp/evil")
      fail("expected a GitCommandException")
    }
    catch (e: GitCommandException) {
      assertTrue(e.message!!.contains("--output=/tmp/evil"))
    }
  }

  @Test
  fun `requireSafeRef accepts an ordinary ref`() {
    ChangesetResolver.requireSafeRef("main") // does not throw
    ChangesetResolver.requireSafeRef("HEAD~1")
  }

  @Test
  fun `isPathNotFoundError recognizes git's actual path-not-found stderr patterns`() {
    assertTrue(ChangesetResolver.isPathNotFoundError(GitCommandException("fatal: path 'x' does not exist in 'HEAD'")))
    assertTrue(ChangesetResolver.isPathNotFoundError(GitCommandException("fatal: path 'x' exists on disk, but not in 'HEAD'")))
    assertFalse(ChangesetResolver.isPathNotFoundError(GitCommandException("fatal: unable to read tree object")))
  }

  // ---- resolveChangedFiles (end-to-end against a fake runner) ----

  /**
   * @param errors explicit error messages for specific arg lists, taking priority over the
   *   default fallback below -- used to simulate a *genuine* git failure (as opposed to the
   *   default fallback's "path not found" shape) for a specific call.
   */
  private class FakeGitCommandRunner(
    private val responses: Map<List<String>, String> = emptyMap(),
    private val errors: Map<List<String>, String> = emptyMap(),
  ) : GitCommandRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(vararg args: String): String {
      val argsList = args.toList()
      calls += argsList
      responses[argsList]?.let { return it }
      errors[argsList]?.let { throw GitCommandException(it) }
      // Mirrors git's real behavior: an unconfigured "show ref:path" call means the path
      // doesn't exist at that ref (the shape ChangesetResolver.readSide must tolerate),
      // while any other unconfigured call is a genuine failure (e.g. an unknown ref).
      if (argsList.size == 2 && argsList[0] == "show" && ":" in argsList[1]) {
        val (ref, path) = argsList[1].split(":", limit = 2)
        throw GitCommandException("fatal: path '$path' does not exist in '$ref'")
      }
      throw GitCommandException("unknown revision or path not in the working tree: $argsList")
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
      // Verify the actual sequence of git invocations, not just the final result -- confirms
      // no working-tree read and no spurious merge-base call happen for a plain two-dot range.
      assertEquals(
        listOf(
          listOf("diff", "--name-only", "main", "feature"),
          listOf("show", "main:a.txt"),
          listOf("show", "feature:a.txt"),
        ),
        git.calls,
      )
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `three-dot range diffs symmetrically and reads old content from the merge base, not the left-hand ref`() {
    val repoRoot = createTempDir()
    try {
      val git = FakeGitCommandRunner(
        mapOf(
          listOf("diff", "--name-only", "main...feature") to "a.txt\n",
          listOf("merge-base", "main", "feature") to "abc123\n",
          listOf("show", "abc123:a.txt") to "content at the merge base",
          listOf("show", "feature:a.txt") to "content on feature",
        )
      )

      val changed = ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "main...feature")

      assertEquals(listOf(ChangedFile("a.txt", oldContent = "content at the merge base", newContent = "content on feature")), changed)
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `a ref that looks like a command-line option is rejected before reaching git`() {
    val repoRoot = createTempDir()
    try {
      val git = FakeGitCommandRunner()

      try {
        ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "--upload-pack=evil")
        fail("expected a GitCommandException")
      }
      catch (e: GitCommandException) {
        assertTrue(e.message!!.contains("--upload-pack=evil"))
      }
      assertTrue("git must never have been invoked with the unsafe ref", git.calls.isEmpty())
    }
    finally {
      repoRoot.deleteRecursively()
    }
  }

  @Test
  fun `a genuine git show failure is rethrown rather than treated as no content`() {
    val repoRoot = createTempDir()
    try {
      val git = FakeGitCommandRunner(
        responses = mapOf(listOf("diff", "--name-only", "HEAD~1") to "a.txt\n"),
        errors = mapOf(listOf("show", "HEAD~1:a.txt") to "fatal: unable to read tree object HEAD~1"),
      )

      try {
        ChangesetResolver(repoRoot, git).resolveChangedFiles(ref = "HEAD~1")
        fail("expected a GitCommandException")
      }
      catch (e: GitCommandException) {
        assertTrue(e.message!!.contains("unable to read tree object"))
      }
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
