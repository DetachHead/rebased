// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import java.io.File
import java.io.IOException

/**
 * One file changed in the changeset being reviewed, together with the two content
 * snapshots to diff.
 *
 * @param repoRelativePath path of the file, relative to the repo root -- matches the shape
 *   expected by [ReviewDiffExtension.FILE_PATH_KEY] and the JSON export format documented
 *   on [ReviewComment].
 * @param oldContent content on the "before" side, or `null` if the file did not exist there
 *   (i.e. the file was added).
 * @param newContent content on the "after" side, or `null` if the file does not exist there
 *   (i.e. the file was deleted).
 */
data class ChangedFile(
  val repoRelativePath: String,
  val oldContent: String?,
  val newContent: String?,
)

/** Thrown when the underlying `git` plumbing fails, e.g. an unknown ref was requested. */
class GitCommandException(message: String) : Exception(message)

/**
 * Runs a single `git` subcommand against a repo checkout and returns its stdout.
 *
 * Extracted behind an interface (rather than [ChangesetResolver] shelling out directly)
 * so the ref-parsing/file-listing logic in [ChangesetResolver] can be unit-tested with a
 * fake implementation -- no real git checkout, git4idea platform services, or `Project`
 * required.
 *
 * Design note (see the plan's own allowance for this): this plugin shells out to the
 * `git` executable directly instead of depending on a git4idea service. No git4idea API in
 * this codebase offers a simple "read this path's content at this ref" primitive that
 * doesn't also require a full `GitRepository`/`Project` context (the closest candidates,
 * `GitFileUtils`/`GitContentRevision`, are internal to the `intellij.vcs.git` module and
 * are wired around a `Project`+`VirtualFile`, not a bare repo root path as available here
 * before any project is guaranteed to be open); adding `intellij.vcs.git` as a module
 * dependency of this plugin for that alone was judged not worth it, so `git show`/
 * `git diff --name-only` are used instead, matching `DiffApplicationBase`'s own precedent
 * of doing file-level plumbing without a heavier VCS service.
 */
interface GitCommandRunner {
  @Throws(GitCommandException::class)
  fun run(vararg args: String): String
}

/** Default [GitCommandRunner]: shells out to the `git` executable found on `PATH`. */
class ProcessGitCommandRunner(private val repoRoot: File) : GitCommandRunner {
  override fun run(vararg args: String): String {
    val process = try {
      ProcessBuilder(listOf("git") + args)
        .directory(repoRoot)
        .start()
    }
    catch (e: IOException) {
      throw GitCommandException("Failed to start git ${args.joinToString(" ")}: ${e.message}")
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw GitCommandException("git ${args.joinToString(" ")} failed: ${stderr.trim().ifEmpty { "exit code $exitCode" }}")
    }
    return stdout
  }
}

/**
 * Resolves a git ref/range argument (as accepted by the `review` CLI command, see
 * [ReviewApplication]) into the list of changed files and their old/new content.
 *
 * Mirrors, for a whole changeset, what `DiffApplicationBase.findFilesOrThrow`/
 * `replaceNullsWithEmptyFile` do for the 2-file `diff` command: resolve "what to compare"
 * and normalize the missing side of an addition/deletion, so callers get a uniform shape
 * to build [com.intellij.diff.requests.DiffRequest]s from.
 *
 * The ref argument's shape matches plain `git diff` semantics, since it is simply handed
 * to `git diff --name-only`:
 *  - `null`/absent: uncommitted changes -- diffs the working tree against `HEAD` (matches
 *    the "auto-detect" convention used elsewhere in this repo, see `commits/SKILL.md`).
 *  - a single ref (e.g. `HEAD~1`, `main`): diffs that ref against the working tree.
 *  - two refs (a `..`/`...` range, e.g. `main..feature`, or two space-separated refs, e.g.
 *    `main feature`): diffs the two refs against each other -- both sides are read via
 *    `git show`, neither comes from the working tree.
 */
class ChangesetResolver(
  private val repoRoot: File,
  private val git: GitCommandRunner = ProcessGitCommandRunner(repoRoot),
) {
  /** @throws GitCommandException if [ref] does not resolve to a known revision. */
  fun resolveChangedFiles(ref: String?): List<ChangedFile> {
    val diffArgs = buildDiffNameOnlyArgs(ref)
    val output = git.run(*diffArgs.toTypedArray())
    val paths = parseNameOnlyOutput(output)
    val (oldRef, newRef) = resolveSides(ref)
    return paths.map { path -> ChangedFile(path, readSide(oldRef, path), readSide(newRef, path)) }
  }

  /**
   * Reads [path]'s content at [ref], or straight off disk if [ref] is `null` (meaning "the
   * working tree"). Returns `null` if the path did not exist on that side (added/deleted
   * file), rather than throwing -- only [resolveChangedFiles]'s `git diff --name-only` call
   * surfaces an unknown-ref error; a missing path at a *known* ref is an expected shape
   * (addition/deletion), not a failure.
   */
  private fun readSide(ref: String?, path: String): String? {
    if (ref == null) {
      val file = File(repoRoot, path)
      return if (file.isFile) file.readText() else null
    }
    return try {
      git.run("show", "$ref:$path")
    }
    catch (e: GitCommandException) {
      null
    }
  }

  companion object {
    /**
     * The `git diff --name-only` args for a given [ref] argument. Internal (not private) so
     * [ChangesetResolverTest] can exercise the pure ref-parsing logic directly.
     */
    internal fun buildDiffNameOnlyArgs(ref: String?): List<String> =
      listOf("diff", "--name-only") + (if (ref == null) listOf("HEAD") else splitRef(ref))

    /** Parses `git diff --name-only` stdout into a list of repo-relative file paths. */
    internal fun parseNameOnlyOutput(output: String): List<String> =
      output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * Resolves the (old, new) ref pair to read file content from for a given [ref]
     * argument. `null` on either side means "the working tree".
     */
    internal fun resolveSides(ref: String?): Pair<String?, String?> {
      if (ref == null) return "HEAD" to null
      val parts = splitRef(ref)
      return if (parts.size == 2) parts[0] to parts[1] else parts[0] to null
    }

    /**
     * Splits a two-ref argument (`"main..feature"`, `"main...feature"`, or
     * `"main feature"`) into its two refs, or returns a single-element list for a plain ref
     * (`"HEAD~1"`). `git diff <a> <b>` (two positional args) behaves the same as
     * `git diff <a>..<b>`, so splitting a `..`/`...` range into two positional args before
     * handing them to `git diff --name-only` is equivalent to passing the range through
     * unsplit.
     */
    private fun splitRef(ref: String): List<String> {
      val trimmed = ref.trim()
      val rangeSeparator = when {
        "..." in trimmed -> "..."
        ".." in trimmed -> ".."
        else -> null
      }
      if (rangeSeparator != null) {
        return trimmed.split(rangeSeparator, limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
      }
      if (trimmed.any { it.isWhitespace() }) {
        return trimmed.split(Regex("\\s+"), limit = 2)
      }
      return listOf(trimmed)
    }
  }
}
