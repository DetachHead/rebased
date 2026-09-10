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
 */
object ReviewCommentsJsonExporter {
  /** Repo-relative path (under [repoRoot]) that the exported JSON is written to. */
  private const val RELATIVE_PATH = ".git/review-comments.json"

  private val gson = GsonBuilder().setPrettyPrinting().create()

  /**
   * Writes [comments] to `<repoRoot>/.git/review-comments.json`, overwriting any previous
   * contents. An empty [comments] list is written as `[]`, not skipped -- so the Claude Code
   * skill can distinguish "review finished, no comments" (empty array) from "review still in
   * progress" (file absent).
   *
   * @throws IOException if the file cannot be written, e.g. `.git` does not exist under
   *   [repoRoot] or the process lacks permission -- callers must surface this rather than
   *   silently no-op, per the plan's test requirements.
   */
  @Throws(IOException::class)
  fun export(comments: List<ReviewComment>, repoRoot: File): File {
    val json = toJson(comments)
    val target = File(repoRoot, RELATIVE_PATH)
    val parent = target.parentFile
    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
      throw IOException("Failed to create directory: ${parent.path}")
    }
    target.writeText(json)
    return target
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
      "line" to line,
      "side" to side.name,
      "text" to text,
    )
}
