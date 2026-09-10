// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.util.Side

/**
 * A single review comment left on one line of one file's diff during a `review` session.
 *
 * Matches the JSON export shape documented in the plan:
 * `{ "filePath": string, "line": number, "side": "LEFT" | "RIGHT", "text": string }` --
 * except that [ReviewCommentsJsonExporter] writes [line] out as a 1-based number (`line + 1`);
 * see its class doc's "Line-number convention" note.
 *
 * @param filePath path of the file the comment was left on, relative to the repo root
 *   (matching the convention used by [ChangesetResolver], added in a later task)
 * @param line **zero-based** document line index on [side], as reported by the diff viewer's
 *   `lineToLocation`/`locationToLine` mapping (see `showCodeReview` in
 *   `com.intellij.collaboration.ui.codereview.diff.viewer`) -- this is the internal/gutter-model
 *   representation, not what ends up in the exported JSON (see [ReviewCommentsJsonExporter])
 * @param side which side of the diff the comment is anchored to
 * @param text the comment body
 */
data class ReviewComment(
  val filePath: String,
  val line: Int,
  val side: Side,
  val text: String,
)
