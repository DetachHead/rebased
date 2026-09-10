// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.google.gson.GsonBuilder
import java.io.File
import java.io.IOException

/**
 * Serializes the comments collected during a `review` session to a fixed, well-known path,
 * per the plan's JSON export format decision:
 * `[{ "filePath": string, "line": number, "side": "LEFT" | "RIGHT", "text": string }, ...]`.
 *
 * Written once, at "Finish Review" time (see [FinishReviewAction]), to
 * `<repo-root>/.git/review-comments.json` -- the repo-root-relative convention documented in
 * the plan's "Open design decisions" table, so the Claude Code skill and this plugin agree on
 * where to find the file without a CLI flag or env var.
 *
 * Worktree/submodule fallback: in a `git worktree`/submodule checkout, `<repoRoot>/.git` is a
 * regular **file** (containing a `gitdir: <path>` pointer), not a directory -- writing under
 * it as if it were always a directory would fail every time (there is already a file occupying
 * that path). Rather than resolving and writing into the real, possibly-shared gitdir the
 * pointer refers to, [export] detects this case (`File(repoRoot, ".git").isFile`) and instead
 * writes to a plain repo-root-relative dotfile, `<repoRoot>/.git-review-comments.json` -- still
 * keyed off the git root as the plan requires, without needing to parse the `gitdir:` pointer
 * file or worry about writing into a gitdir shared by multiple worktrees. Callers/consumers
 * (e.g. the `diff-review` Claude Code skill) that hardcode the `.git/review-comments.json` path
 * must also check this fallback path when `.git` is not a directory.
 *
 * Line-number convention: [ReviewComment.line] is 0-based internally (see its doc comment),
 * but this exporter writes it out as a **1-based** line number (`line + 1`), matching the
 * file's on-disk line numbers and the `file:line` annotation convention the `diff-review`
 * Claude Code skill formats it as. Keeping the internal/gutter-model representation 0-based
 * (matching platform `Editor`/`Document` convention) while converting only at the JSON-export
 * boundary avoids threading a "which convention is this number in" question through the rest
 * of the plugin.
 *
 * Cleared at the start of every session: [clear] is called by [ReviewApplication] right before
 * it opens the diff viewer for a new `review` invocation, deleting any export left behind by an
 * earlier/interrupted session (one whose window was closed without "Finish Review"). This
 * guarantees the file a consumer reads after a session ends was produced by *that* session's own
 * "Finish Review" click (or is simply absent), never a stale leftover from a previous one.
 */
object ReviewCommentsJsonExporter {
  /** Repo-relative path (under [repoRoot]) that the exported JSON is written to when `.git`
   * is an ordinary directory. */
  private const val RELATIVE_PATH = ".git/review-comments.json"

  /** Fallback repo-relative path used when `.git` is a file (worktree/submodule checkout),
   * not a directory -- see the class doc's "Worktree/submodule fallback" note. */
  private const val WORKTREE_FALLBACK_RELATIVE_PATH = ".git-review-comments.json"

  private val gson = GsonBuilder().setPrettyPrinting().create()

  /**
   * Writes [comments] to `<repoRoot>/.git/review-comments.json` (or, in a worktree/submodule
   * checkout where `.git` is a file rather than a directory, to
   * `<repoRoot>/.git-review-comments.json` -- see the class doc), overwriting any previous
   * contents. An empty [comments] list is written as `[]`, not skipped -- so the Claude Code
   * skill can distinguish "review finished, no comments" (empty array) from "review still in
   * progress" (file absent).
   *
   * @throws IOException if the file cannot be written, e.g. the process lacks permission --
   *   callers must surface this rather than silently no-op, per the plan's test requirements.
   */
  @Throws(IOException::class)
  fun export(comments: List<ReviewComment>, repoRoot: File): File {
    val json = toJson(comments)
    val target = resolveTargetFile(repoRoot)
    val parent = target.parentFile
    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
      throw IOException("Failed to create directory: ${parent.path}")
    }
    target.writeText(json)
    return target
  }

  /**
   * Deletes any previously-exported `review-comments.json` (at either the normal or the
   * worktree-fallback path -- see the class doc) under [repoRoot], if present. Called by
   * [ReviewApplication] before it opens a new `review` session's diff viewer, so a stale file
   * from an earlier/interrupted session can never be mistaken for this session's export -- see
   * the class doc's "Cleared at the start of every session" note.
   *
   * No-op (does not throw) if there is nothing to delete, or if deletion fails -- a failed
   * best-effort cleanup at session *start* shouldn't block opening the review window; a stale
   * file surviving is far less harmful than [export] failing outright at session *end* (which
   * does still throw, per its own contract).
   */
  fun clear(repoRoot: File) {
    resolveTargetFile(repoRoot).delete()
  }

  /**
   * Resolves the JSON output path for [repoRoot], accounting for the worktree/submodule case
   * where `<repoRoot>/.git` is a regular file rather than a directory (see the class doc).
   */
  private fun resolveTargetFile(repoRoot: File): File {
    val gitPath = File(repoRoot, ".git")
    return if (gitPath.isFile) File(repoRoot, WORKTREE_FALLBACK_RELATIVE_PATH) else File(repoRoot, RELATIVE_PATH)
  }

  /**
   * Pure serialization logic, split out from [export] so it can be unit-tested without
   * touching the filesystem.
   */
  internal fun toJson(comments: List<ReviewComment>): String =
    gson.toJson(comments.map { it.toExportEntry() })

  private fun ReviewComment.toExportEntry(): Map<String, Any> =
    linkedMapOf(
      "filePath" to filePath,
      // +1: see the class doc's "Line-number convention" note -- [line] is 0-based internally.
      "line" to line + 1,
      "side" to side.name,
      "text" to text,
    )
}
