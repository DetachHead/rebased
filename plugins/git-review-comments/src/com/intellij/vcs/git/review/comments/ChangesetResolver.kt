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
 * One changed-file record from `git diff --name-status -M -z` output: the old and new
 * repo-relative paths for a changed file. Equal for every change type except a detected
 * rename, where [oldPath] is the pre-rename path (read for the "old" side's content) and
 * [newPath] is the post-rename path (read for the "new" side's content, and used as the
 * diff's display/comment-anchoring path).
 */
data class DiffEntry(val oldPath: String, val newPath: String)

/**
 * The result of splitting a ref/range argument into its component ref(s).
 *
 * @param old the single ref (plain ref), or the left-hand ref of a range
 * @param new the right-hand ref of a range, or `null` for a plain single ref
 * @param isMergeBaseRange `true` for a `...` (merge-base/symmetric) range, as opposed to a
 *   `..` range or two space-separated refs (both of which behave like a plain two-ref diff)
 */
data class SplitRef(val old: String, val new: String?, val isMergeBaseRange: Boolean = false)

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
 * `git diff --name-status -M` are used instead, matching `DiffApplicationBase`'s own precedent
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
    // Read stdout and stderr concurrently on separate threads. Reading one stream fully
    // before touching the other (as a naive sequential implementation would) can deadlock:
    // if git writes enough to the *other* stream to fill its OS pipe buffer while this
    // thread is still blocked reading the first one, git blocks writing and this thread
    // blocks reading -- neither side makes progress.
    var stdout = ""
    var stderr = ""
    val stdoutThread = Thread({ stdout = process.inputStream.bufferedReader().readText() }, "git-stdout-reader")
    val stderrThread = Thread({ stderr = process.errorStream.bufferedReader().readText() }, "git-stderr-reader")
    stdoutThread.start()
    stderrThread.start()
    stdoutThread.join()
    stderrThread.join()
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
 * to `git diff --name-status -M`:
 *  - `null`/absent: uncommitted changes -- diffs the working tree against `HEAD` (matches
 *    the "auto-detect" convention used elsewhere in this repo, see `commits/SKILL.md`).
 *  - a single ref (e.g. `HEAD~1`, `main`): diffs that ref against the working tree.
 *  - a `..` range (e.g. `main..feature`) or two space-separated refs (e.g. `main feature`):
 *    diffs the two refs directly against each other -- both sides are read via `git show`,
 *    neither comes from the working tree.
 *  - a `...` range (e.g. `main...feature`): symmetric/merge-base diff, matching plain
 *    `git diff a...b` semantics -- the "old" side actually read is the merge base of the two
 *    refs (computed via `git merge-base`), not the left-hand ref itself.
 *
 * Note on consistency: [resolveChangedFiles] fetches the changed-file list with one
 * `git diff --name-status -M` call and then reads each file's content in a separate, later
 * step.
 * A concurrent working-tree edit between those two steps can in principle produce an
 * inconsistent result (e.g. a file listed as changed whose content has since reverted) --
 * this is an accepted limitation for a manually-invoked review CLI (mirroring
 * `DiffApplicationBase`'s own non-atomic file reads), not addressed by snapshotting the
 * working tree (e.g. via `git stash create`), which was judged not worth the added
 * complexity for this workflow.
 */
class ChangesetResolver(
  private val repoRoot: File,
  private val git: GitCommandRunner = ProcessGitCommandRunner(repoRoot),
) {
  /**
   * @throws GitCommandException if [ref] does not resolve to a known revision.
   *
   * Rename handling: file listing is done via `git diff --name-status -M`, not
   * `--name-only`, so renamed files surface as an explicit `oldPath` -> `newPath` pair (see
   * [DiffEntry]) rather than a single path. Without this, a rename's old content would be
   * looked up at its *new* path -- which never existed on the old side -- and the whole file
   * would show as a fabricated full addition instead of a rename+modify diff.
   */
  fun resolveChangedFiles(ref: String?): List<ChangedFile> {
    val diffArgs = buildDiffNameStatusArgs(ref)
    val output = git.run(*diffArgs.toTypedArray())
    val entries = parseNameStatusOutput(output)
    val (oldRef, newRef) = resolveContentSides(ref)
    return entries.map { entry ->
      ChangedFile(entry.newPath, readSide(oldRef, entry.oldPath), readSide(newRef, entry.newPath))
    }
  }

  /**
   * Resolves the (old, new) ref pair to read file content from for a given [ref] argument.
   * `null` on either side means "the working tree". For a `...` (merge-base) range, resolves
   * the actual merge-base commit via `git merge-base` rather than using the left-hand ref
   * directly, since that is what `git diff a...b` itself compares against.
   */
  private fun resolveContentSides(ref: String?): Pair<String?, String?> {
    if (ref == null) return "HEAD" to null
    val split = splitRef(ref)
    requireSafeRef(split.old)
    split.new?.let(::requireSafeRef)
    if (split.new == null) return split.old to null
    val oldRef = if (split.isMergeBaseRange) git.run("merge-base", split.old, split.new).trim() else split.old
    return oldRef to split.new
  }

  /**
   * Reads [path]'s content at [ref], or straight off disk if [ref] is `null` (meaning "the
   * working tree"). Returns `null` if the path did not exist on that side (added/deleted
   * file), rather than throwing -- only [resolveChangedFiles]'s `git diff --name-status -M`
   * call and [resolveContentSides]'s `git merge-base` call are expected to surface an unknown-ref
   * error here; a missing path at a *known* ref is an expected shape (addition/deletion),
   * not a failure. Any other git failure (bad object, permissions, etc.) is rethrown rather
   * than silently treated as "no content", per [isPathNotFoundError].
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
      if (isPathNotFoundError(e)) null else throw e
    }
  }

  companion object {
    /**
     * The `git diff --name-status -M -z` args for a given [ref] argument. `-M` (rename
     * detection) is what makes renamed files surface as an explicit `R<score>` status record
     * with both the old and new path (see [parseNameStatusOutput]/[DiffEntry]), instead of a
     * bare path that [readSide] would otherwise treat as "newly added" on the old side. `-z`
     * makes git emit that output as raw, NUL-separated (`\u0000`) fields instead of
     * newline-separated, tab-delimited text -- without it, git C-style-quotes/escapes any
     * path containing non-ASCII or otherwise "unusual" bytes (e.g. `café.txt` becomes the
     * literal 14-character string `"caf\303\251.txt"` in the output), which a naive
     * tab/newline-based parser would read verbatim as the wrong path. `-z` output is never
     * quoted, so [parseNameStatusOutput] gets the real bytes directly.
     * Internal (not private) so [ChangesetResolverTest] can exercise the pure ref-parsing
     * logic directly.
     *
     * @throws GitCommandException if [ref] (or either half of a two-ref range) looks like a
     *   command-line option rather than a revision (see [requireSafeRef]).
     */
    internal fun buildDiffNameStatusArgs(ref: String?): List<String> {
      if (ref == null) return listOf("diff", "--name-status", "-M", "-z", "HEAD")
      val split = splitRef(ref)
      requireSafeRef(split.old)
      split.new?.let(::requireSafeRef)
      return listOf("diff", "--name-status", "-M", "-z") + when {
        // `git diff a...b` (merge-base/symmetric diff) is only valid git syntax as a single
        // positional argument -- splitting it into two positional refs (`a b`) silently
        // changes the semantics to a plain two-ref diff, so it is passed through unsplit.
        split.isMergeBaseRange -> listOf("${split.old}...${split.new}")
        split.new != null -> listOf(split.old, split.new)
        else -> listOf(split.old)
      }
    }

    /**
     * Parses `git diff --name-status -M -z` stdout into [DiffEntry] pairs.
     *
     * With `-z`, git emits raw, NUL (`\u0000`)-terminated fields instead of newline-separated,
     * tab-delimited lines -- there is no tab separator and no line-based structure at all, and
     * paths are never C-style-quoted/escaped (see [buildDiffNameStatusArgs] for why that
     * matters). An ordinary change is the two-field record `<status>\u0000<path>\u0000` (`A`,
     * `M`, `D`, ...); a detected rename or copy is the three-field record
     * `<status><score>\u0000<oldPath>\u0000<newPath>\u0000` (e.g.
     * `R100\u0000old/path.kt\u0000new/path.kt\u0000`) -- copy detection is not requested (`-M`,
     * not `-C`), so a `C` status is not expected in practice, but is still handled the same way
     * defensively. Records are simply concatenated one after another with no extra separator,
     * so the field list must be walked status-by-status (consuming 1 or 2 path fields per
     * status, depending on its first letter) rather than split on any fixed delimiter.
     */
    internal fun parseNameStatusOutput(output: String): List<DiffEntry> {
      val fields = output.split('\u0000').toMutableList()
      // `-z` NUL-terminates every field, including the last one, so splitting on it leaves a
      // trailing empty field that must be dropped rather than treated as a spurious record.
      if (fields.isNotEmpty() && fields.last().isEmpty()) fields.removeAt(fields.size - 1)
      val entries = mutableListOf<DiffEntry>()
      var i = 0
      while (i < fields.size) {
        val status = fields[i]
        i++
        if ((status.startsWith("R") || status.startsWith("C")) && i + 1 < fields.size) {
          entries.add(DiffEntry(oldPath = fields[i], newPath = fields[i + 1]))
          i += 2
        }
        else if (i < fields.size) {
          val path = fields[i]
          entries.add(DiffEntry(oldPath = path, newPath = path))
          i++
        }
      }
      return entries
    }

    /**
     * Splits a two-ref argument (`"main..feature"`, `"main...feature"`, or
     * `"main feature"`) into its two refs, or returns a single-ref result for a plain ref
     * (`"HEAD~1"`). Internal (not private) so [ChangesetResolverTest] can exercise this
     * directly.
     */
    internal fun splitRef(ref: String): SplitRef {
      val trimmed = ref.trim()
      if ("..." in trimmed) {
        val parts = trimmed.split("...", limit = 2).map { it.trim() }
        if (parts.size == 2 && parts.all { it.isNotEmpty() }) return SplitRef(parts[0], parts[1], isMergeBaseRange = true)
      }
      if (".." in trimmed) {
        val parts = trimmed.split("..", limit = 2).map { it.trim() }
        if (parts.size == 2 && parts.all { it.isNotEmpty() }) return SplitRef(parts[0], parts[1])
      }
      if (trimmed.any { it.isWhitespace() }) {
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        return SplitRef(parts[0], parts.getOrNull(1))
      }
      return SplitRef(trimmed, null)
    }

    /**
     * Rejects a ref that looks like a command-line option (starts with `-`), since handing
     * one straight to `git` risks argument injection -- e.g. a ref of `--output=/some/path`
     * passed to `git show` can be interpreted as an option that forces an arbitrary file
     * write, rather than as a (nonexistent) revision name. A trailing `--`
     * end-of-options separator isn't syntactically valid for every git invocation shape used
     * here (e.g. `git show <ref>:<path>` is a single positional argument, not a bare ref), so
     * refs are validated up front instead.
     *
     * @throws GitCommandException if [ref] starts with `-`
     */
    internal fun requireSafeRef(ref: String) {
      if (ref.startsWith("-")) {
        throw GitCommandException("refusing to treat '$ref' as a git ref: it looks like a command-line option")
      }
    }

    /**
     * Patterns git's own stderr uses (as of the git versions this was checked against) when
     * `git show <ref>:<path>` fails because the path simply doesn't exist at that ref --
     * as opposed to some other failure (bad ref, corrupt object, permissions, etc.), which
     * must be surfaced rather than treated as "no content".
     */
    private val PATH_NOT_FOUND_MESSAGE_FRAGMENTS = listOf(
      "does not exist in",
      "exists on disk, but not in",
    )

    /** Internal (not private) so [ChangesetResolverTest] can exercise this directly. */
    internal fun isPathNotFoundError(e: GitCommandException): Boolean {
      val message = e.message ?: return false
      return PATH_NOT_FOUND_MESSAGE_FRAGMENTS.any { it in message }
    }
  }
}
