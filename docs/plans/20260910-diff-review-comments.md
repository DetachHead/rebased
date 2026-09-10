# Diff Review Comments: Rebased Plugin + Claude Code Integration

## Overview

Rebased already opens a diff for two files from the terminal (`DiffApplication`, backing
the `diff` CLI command). What's missing is a review loop: leave inline comments on
specific lines of a changeset diff, export them, and hand them back to an AI coding
assistant (Claude Code) so it can act on them, then let the reviewer re-check the fix.

This plan adds:
1. A new bundled plugin (`plugins/git-review-comments`) that wires Rebased's existing
   `DiffExtension` hook to the platform's existing code-review-in-editor toolkit
   (`platform/collaboration-tools`) to get a per-line gutter "add comment" affordance in
   any diff viewer, for free, without writing gutter-painting code from scratch.
2. A new CLI entry point (`ApplicationStarter`, command name `review`) that opens a
   **multi-file** diff session for a git changeset (unlike the existing `diff` command,
   which only accepts exactly two file paths) and a "Finish Review" action that
   serializes all comments collected across every file in that session to a single JSON
   file at a fixed, well-known path.
3. A repo-local Claude Code skill (`.claude/skills/diff-review/SKILL.md`, mirrored under
   `.agents/skills/diff-review/SKILL.md` per this repo's existing dual-location
   convention) that launches `rebased review <ref>`, waits for the user, reads the JSON,
   turns each comment into a `file:line` annotation, plans + applies fixes, and loops by
   relaunching the review until the user finishes with no new comments.

## Context (from discovery)

- CLI diff launcher already exists: `platform/diff-impl/src/com/intellij/diff/applications/DiffApplication.kt`
  — `commandName = "diff"`, `ApplicationStarterBase(0, 2, 3)` (takes exactly 2 file args),
  builds a `DiffRequestChain` and calls `DiffManagerEx.getInstance().showDiffBuiltin(...)`.
  Registered via `applicationStarter` extension in
  `platform/platform-resources/src/META-INF/PlatformExtensions.xml`.
- Extension point for hooking any diff viewer: `platform/diff-api/src/com/intellij/diff/DiffExtension.java`
  (`EP_NAME = "com.intellij.diff.DiffExtension"`, `onViewerCreated(viewer, context, request)`),
  works with `SimpleDiffViewer`, `SimpleOnesideDiffViewer`, `UnifiedDiffViewer`.
- Reusable code-review-in-editor toolkit already in the platform, used today by the
  GitHub/GitLab PR review plugins, in `platform/collaboration-tools/src/com/intellij/collaboration/ui/codereview/editor/`:
  - `CodeReviewCommentableEditorModel.kt` — `canCreateComment`, `requestNewComment`, `cancelNewComment`
  - `CodeReviewEditorGutterControlsModel.kt` — `gutterControlsState: StateFlow<ControlsState?>`,
    `linesWithComments`, `isLineCommentable`, `toggleComments`
  - `CodeReviewEditorGutterControlsRenderer.kt` — the actual gutter paint + click handler
    (`LineMarkerRenderer`/`LineMarkerRendererEx`/`ActiveGutterRenderer`)
  - `action/CodeReviewEditorNewCommentAction.kt` — keyboard-triggered "add comment on
    current line" action
  - Comment bubble/textfield widgets: `comment/CodeReviewCommentTextFieldFactory.kt`,
    `comment/CodeReviewCommentUIUtil.kt`
  - Blueprint to mirror almost exactly:
    `plugins/github/github-core/src/org/jetbrains/plugins/github/pullrequest/ui/diff/GHPRReviewDiffExtension.kt`
    (registers as a `DiffExtension`, wires the gutter model, calls
    `com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview`).
  - `plugins/github` and `plugins/gitlab` remain in this checkout despite the "git-only"
    fork framing — out of scope for this plan (confirmed with user); `collaboration-tools`
    has no GitHub/GitLab-specific coupling so depending on it doesn't drag those in.
- AnAction + modular plugin-content conventions, from `git4idea`:
  - Action class: `plugins/git4idea/backend/src/actions/GitInit.java` — `extends DumbAwareAction`,
    overrides `update()` and `getActionUpdateThread()` (returns `ActionUpdateThread.BGT`).
  - Registration: `plugins/git4idea/backend/resources/intellij.vcs.git.backend.xml:26-30`
    (`<actions>` block, `<action id=... class=... />`, `<add-to-group .../>`).
  - Root descriptor `plugins/git4idea/resources/META-INF/plugin.xml` uses
    `<content namespace="jetbrains">` with `<module name="..."/>` submodules.
- `actions` skill conventions: no-arg `AnAction` constructors, no `Presentation` built in
  the constructor, action text/description via message bundle
  (`action.<id>.text` / `action.<id>.description`), plugin.xml sets `id` + `icon`.
- Small standalone bundled-plugin template to mirror structurally:
  `plugins/git-modal-commit/` — has its own `.iml`
  (`intellij.vcs.git.commit.modal.iml`, depends on `intellij.platform.vcs`,
  `intellij.platform.vcs.impl`, `intellij.platform.core`, etc.), `resources/META-INF/plugin.xml`
  (`<dependencies><module name=.../></dependencies>`, `<extensions>` block), and
  `plugin-content.yaml` (`- name: lib/<jar>.jar` / `modules: [...]`) to get included in the
  product layout.
- Existing repo-local skill format to mirror for the Claude Code side:
  `.claude/skills/commits/SKILL.md` and its `.agents/skills/commits/SKILL.md` mirror
  (this repo keeps both locations in sync via `.ai/render-guides.mjs`, per the
  `<!-- Generated by community/.ai/render-guides.mjs -->` header seen in `actions/SKILL.md`).

### Open design decisions (resolved during planning)

| Question | Decision |
|---|---|
| `DiffApplication` only takes 2 file paths — how to review a multi-file changeset? | **New `ApplicationStarter`**, command name `review`, that resolves a git ref/changeset into a multi-file `DiffRequestChain` and opens one review session spanning all changed files. `DiffApplication`/`diff` is left untouched. |
| Where does the exported comments JSON live? | **Fixed path in repo temp dir**: `<repo-root>/.git/review-comments.json`. No CLI flag, no env var — the Claude Code skill and the plugin both hardcode this convention, keyed off the git root so it works from any subdirectory. |
| `plugins/github` / `plugins/gitlab` still present in this fork | **Out of scope.** Not touched by this plan. |
| Testing approach | **Regular** (code first, then tests) — standard for this codebase's platform/plugin tests. |

## Development Approach

- Testing approach: **Regular** (code first, then tests).
- Complete each task fully, with passing tests, before starting the next.
- New code lives entirely in a new module (`plugins/git-review-comments`) — no edits to
  `diff-impl`/`diff-api`/`collaboration-tools` core, only additive registration via the
  existing `DiffExtension` EP and a new `ApplicationStarter`.
- Keep the plugin JVM-side format (JSON export) intentionally minimal:
  `[{ "filePath": string, "line": number, "side": "LEFT" | "RIGHT", "text": string }]`.
- Every task ends with tests passing before moving on.

## Testing Strategy

- **Unit tests**: for the JSON export/serialization logic, the comment model
  (`InMemoryCodeReviewEditorGutterControlsModel` or equivalent), and the changeset
  resolution logic in the new `ApplicationStarter` (given a ref, produces the expected
  file list / `DiffRequestChain` inputs) — plain JUnit, no platform fixture needed where
  logic is pure.
- **Platform/light tests**: for the `DiffExtension` wiring itself (does it attach a
  gutter-commentable model to a viewer created for a given `DiffRequest`) — using the
  existing light platform test fixtures already used elsewhere under
  `platform/diff-impl` tests, if such a fixture pattern exists (verify during Task 2 and
  adjust if the existing diff-impl test infra doesn't support this scenario cheaply —
  falling back to a plain unit test against the model/extension logic in isolation is
  acceptable if a full viewer-creation integration test proves impractical).
- **Skill-level testing**: manual — the Claude Code skill is markdown/shell, not compiled
  code; verified via the acceptance task (Task 5) by running the full loop end-to-end.
- No e2e UI test framework (Playwright/Cypress) applies here; this is a desktop IDE
  plugin, not a web app.

## What Goes Where

- **Implementation Steps**: module scaffolding, `DiffExtension` + gutter model, changeset
  `ApplicationStarter`, `Finish Review` action + JSON export, the Claude Code skill files.
- **Post-Completion**: building/running a local Rebased distribution to manually verify
  the gutter UI feel, deciding on an icon for the new action, and (optionally, later)
  stripping `plugins/github`/`plugins/gitlab` from the fork.

## Implementation Steps

### Task 1: Scaffold `plugins/git-review-comments` module

**Files:**
- Create: `plugins/git-review-comments/intellij.vcs.git.review.comments.iml`
- Create: `plugins/git-review-comments/BUILD.bazel`
- Create: `plugins/git-review-comments/plugin-content.yaml`
- Create: `plugins/git-review-comments/resources/META-INF/plugin.xml`
- Create: `plugins/git-review-comments/resources/messages/GitReviewCommentsBundle.properties`
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/GitReviewCommentsBundle.kt`

- [x] create the `.iml` mirroring `plugins/git-modal-commit/intellij.vcs.git.commit.modal.iml`,
      depending on `intellij.platform.diff`, `intellij.platform.diff.impl`,
      `intellij.platform.collaborationTools`, `intellij.platform.vcs.impl`,
      `intellij.platform.core`, `kotlin-stdlib`
- [x] create `BUILD.bazel` mirroring `plugins/git-modal-commit/BUILD.bazel`'s structure,
      with matching module deps plus a `gson` library dependency for JSON export
- [x] create `plugin-content.yaml` (`- name: lib/vcs-git-review-comments.jar`,
      `modules: [{ name: intellij.vcs.git.review.comments }]`)
- [x] create `resources/META-INF/plugin.xml` skeleton (`<idea-plugin>`, `<id>`, `<name>`,
      `<vendor>`, `<dependencies>` block, empty `<extensions>`/`<actions>` blocks, and
      `<resource-bundle>messages.GitReviewCommentsBundle</resource-bundle>`), following
      `plugins/git-modal-commit/resources/META-INF/plugin.xml`
- [x] create the `GitReviewCommentsBundle` message bundle class + `.properties` file
      (empty placeholders for now; populated in Tasks 3–4 per the `actions` skill
      convention of text/description via bundle, not hardcoded)
- [x] write a smoke test that loads the module's plugin descriptor in a light test
      fixture and asserts it parses without errors
- [x] write a test asserting the `BUILD.bazel` module name matches the `.iml` module name
      (simple string-based sanity check, prevents drift between the two build systems)
- [x] run tests — must pass before Task 2 (validated via XML well-formedness checks and a
      Python simulation of both tests' logic against the real files, since this sandbox has
      no JVM runtime or Bazel binary available — see progress log for details)

### Task 2: `DiffExtension` + in-memory gutter comment model

**Files:**
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/ReviewComment.kt`
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/InMemoryReviewCommentStore.kt`
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/ReviewDiffExtension.kt`
- Modify: `plugins/git-review-comments/resources/META-INF/plugin.xml`
- Create: `plugins/git-review-comments/test/com/intellij/vcs/git/review/comments/InMemoryReviewCommentStoreTest.kt`

- [x] define `ReviewComment(filePath: String, line: Int, side: Side, text: String)` data
      class (reuse `com.intellij.diff.util.Side` from `intellij.platform.diff`)
- [x] implement `InMemoryReviewCommentStore` — a session-scoped, mutable collection of
      `ReviewComment`s shared across every file diff opened in one `review` session,
      exposing add/remove/list operations (this is the piece `GHPRReviewDiffExtension.kt`
      backs with a real backend API call; here it's just an in-memory list, since the
      "backend" is a local JSON file written once at Finish Review time)
- [x] implement `ReviewDiffExtension : DiffExtension`, mirroring
      `GHPRReviewDiffExtension.kt`: on `onViewerCreated`, build a
      `CodeReviewCommentableEditorModel` + `CodeReviewEditorGutterControlsModel`
      implementation backed by `InMemoryReviewCommentStore` (filtered to the current
      file's `filePath`), and attach it the same way `GHPRReviewDiffExtension` calls
      `showCodeReview` — reusing `CodeReviewEditorGutterControlsRenderer` for the actual
      gutter paint/click handling instead of writing a new renderer. Scope note (see
      code doc on `ReviewDiffExtension`): `isLineCommentable` allows any line present in
      the document rather than being restricted to changed-line ranges — computing
      changed-line ranges generically across `SimpleDiffViewer`/`SimpleOnesideDiffViewer`/
      `UnifiedDiffViewer` has no shared viewer-type-agnostic API and is deferred as a
      follow-up, per this task's own allowance to narrow scope if a full integration
      proves impractical.
- [x] register `ReviewDiffExtension` under `<extensions defaultExtensionNs="com.intellij">
      <diff.DiffExtension implementation="..."/> </extensions>` in `plugin.xml`
- [x] write unit tests for `InMemoryReviewCommentStore` (add/remove/list, filtering by
      file path, success + edge cases: duplicate line comments, empty store)
- [x] write a focused unit test against `InMemoryGutterControlsModel`'s (the model class
      backing `ReviewDiffExtension`) construction logic in isolation — a full
      viewer-creation light platform test proved impractical without `ChangesetResolver`/
      `ReviewApplication` (a later task) to build a real `DiffRequestChain`; verifies
      `isLineCommentable`/`linesWithComments` against fake `locationToLine`/
      `lineToLocation` mappings of the same shape `showCodeReview` provides
- [x] run tests — must pass before Task 3 (validated via read-verification against the
      real API signatures in `DiffExtension.java`, `CodeReviewCommentableEditorModel.kt`,
      `CodeReviewEditorGutterControlsModel.kt`, `CodeReviewEditorGutterControlsRenderer.kt`,
      `diffViewerUtil.kt`, `GHPRReviewDiffExtension.kt`, and `GitLabMergeRequestDiffExtension.kt`,
      plus a Python simulation of both new test files' logic against equivalent Python
      re-implementations of `InMemoryReviewCommentStore`/`InMemoryGutterControlsModel` — see
      progress log for details; no JVM/Bazel toolchain available in this sandbox to actually
      compile/run the Kotlin)

### Task 3: Changeset `ApplicationStarter` (`review` CLI command)

**Files:**
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/ReviewApplication.kt`
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/ChangesetResolver.kt`
- Modify: `plugins/git-review-comments/resources/META-INF/plugin.xml`
- Create: `plugins/git-review-comments/test/com/intellij/vcs/git/review/comments/ChangesetResolverTest.kt`

- [ ] implement `ChangesetResolver` — given a repo root and a ref/range argument (e.g.
      `HEAD~1`, `main`, or nothing for uncommitted changes, matching the auto-detect
      conventions already used by the `commits`/other review-style skills in this repo),
      resolve the set of changed files and, per file, the two content sources to diff
      (mirrors what `DiffApplication.findFilesOrThrow`/`replaceNullsWithEmptyFile` in
      `platform/diff-impl/src/com/intellij/diff/applications/DiffApplicationBase.java`
      already does for the 2-file case — reuse those helpers where the shapes line up)
  - Note: this task's exact git plumbing depends on the git4idea APIs available in this
    codebase for reading historical file content; if a suitable existing service isn't
    found, fall back to shelling out to `git show <ref>:<path>` for old content, `git
    diff --name-only` for the file list.
- [ ] implement `ReviewApplication : ApplicationStarterBase(...)` with `commandName = "review"`,
      accepting one optional ref argument, using `ChangesetResolver` to build one
      multi-file `DiffRequestChain` (or one `showDiffBuiltin` call per file if the
      platform's `DiffRequestChain` doesn't support a clean multi-file "browse next/prev
      file" UX — verify during implementation and note in Progress Tracking if scope
      changes), opening the chain via `DiffManagerEx.getInstance().showDiffBuiltin(...)`,
      same as `DiffApplication`
- [ ] register `ReviewApplication` as an `applicationStarter` extension in `plugin.xml`,
      following the pattern used for `diff` in
      `platform/platform-resources/src/META-INF/PlatformExtensions.xml`
- [ ] write unit tests for `ChangesetResolver` (uncommitted changes, ref vs ref, ref vs
      working tree, empty changeset, nonexistent ref → error case)
- [ ] write a test for `ReviewApplication`'s argument parsing (0 args = uncommitted, 1 arg
      = ref) separate from the actual diff-opening side effect
- [ ] run tests — must pass before Task 4

### Task 4: "Finish Review" action + JSON export

**Files:**
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/FinishReviewAction.kt`
- Create: `plugins/git-review-comments/src/com/intellij/vcs/git/review/comments/ReviewCommentsJsonExporter.kt`
- Modify: `plugins/git-review-comments/resources/META-INF/plugin.xml`
- Modify: `plugins/git-review-comments/resources/messages/GitReviewCommentsBundle.properties`
- Create: `plugins/git-review-comments/test/com/intellij/vcs/git/review/comments/ReviewCommentsJsonExporterTest.kt`

- [ ] implement `ReviewCommentsJsonExporter` — serializes the current
      `InMemoryReviewCommentStore` contents to
      `[{ "filePath": ..., "line": ..., "side": ..., "text": ... }, ...]` via `gson`, writing
      to `<repo-root>/.git/review-comments.json` (resolve repo root the same way
      `ChangesetResolver` does)
- [ ] implement `FinishReviewAction : DumbAwareAction` (no-arg constructor, no
      `Presentation` in constructor, per `actions` skill conventions) — on
      `actionPerformed`, calls the exporter, then closes the review session/diff window
  - overrides `update()` to enable only when a `ReviewDiffExtension`-backed session is
    active (comment store non-empty, or session marker present)
  - overrides `getActionUpdateThread()` returning `ActionUpdateThread.BGT`
- [ ] register `FinishReviewAction` in `plugin.xml` under `<actions>`, added to the diff
      viewer toolbar/popup group referenced in `DiffExtension.java`'s javadoc, with
      `action.Git.Review.FinishReview.text` / `.description` keys added to
      `GitReviewCommentsBundle.properties`
- [ ] write unit tests for `ReviewCommentsJsonExporter` (empty store → empty array file,
      multiple comments across multiple files, special characters in comment text escape
      correctly, write failure surfaces an error rather than silently no-op)
- [ ] write a test for `FinishReviewAction.update()`'s enablement logic (enabled only with
      an active review session)
- [ ] run tests — must pass before Task 5

### Task 5: Claude Code skill — `diff-review`

**Files:**
- Create: `.claude/skills/diff-review/SKILL.md`
- Create: `.agents/skills/diff-review/SKILL.md`

- [ ] write `SKILL.md` (both locations, matching content per this repo's
      `render-guides.mjs` dual-location convention seen in `actions/SKILL.md`'s generated
      header) describing:
  - activation triggers: "diff review", "review my changes", "review this with rebased"
  - Step 1: determine the ref to review — reuse the same auto-detect logic style as
    `commits/SKILL.md` (uncommitted changes vs branch-vs-main vs explicit ref argument)
  - Step 2: launch `rebased review <ref>` (or no ref for uncommitted), documenting that
    this blocks until the user closes the review window — same long-running-command
    caveat pattern as other terminal-overlay skills in this environment (set a generous
    timeout, do not backgrounded-poll)
  - Step 3: after the process returns, read `<repo-root>/.git/review-comments.json`; if
    absent or empty, the review is complete with no comments — stop here
  - Step 4: format each `{filePath, line, side, text}` entry as a `file:line` annotation;
    enter plan mode listing each annotation and its intended fix, get user approval
  - Step 5: apply the approved fixes to the real source files
  - Step 6: delete/clear `.git/review-comments.json`, relaunch `rebased review <ref>` so
    the user can verify fixes and leave more comments
  - Step 7: loop until the JSON file comes back empty/absent — inform the user review is
    complete
- [ ] write tests as a documented manual walkthrough in the plan's acceptance task
      (Task 6) — skill files are markdown, not executable code, so no automated unit test
      applies here; this is intentional and matches how other markdown-only skills in
      this repo (e.g. `commits`) are validated
- [ ] run tests — must pass before Task 6 (module test suite from Tasks 1–4 must still
      be green)

### Task 6: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented: gutter comment UI, changeset
      review CLI command, Finish Review JSON export, Claude Code skill loop
- [ ] verify edge cases: empty changeset, comment on a deleted line, comment on an added
      line, re-running review after fixes shows an updated diff
- [ ] build a local Rebased distribution and manually run
      `rebased review HEAD~1` (or uncommitted changes) end-to-end: add a comment via the
      gutter icon, trigger Finish Review, confirm `.git/review-comments.json` contents
      match what was entered
- [ ] manually invoke the `diff-review` Claude Code skill against a real set of changes
      and confirm the full loop (comment → plan → fix → re-review → done) completes
- [ ] run full module test suite for `plugins/git-review-comments`
- [ ] verify test coverage: every new class in Tasks 2–4 has at least one corresponding
      test class

### Task 7: [Final] Update documentation

- [ ] update `plugins/git-review-comments`'s `plugin.xml` `<description>` if scope shifted
      during implementation
- [ ] add a short section to the top-level `README.md` (or `CONTRIBUTING.md`, whichever
      documents bundled plugins) mentioning the `review` CLI command and the
      `diff-review` Claude Code skill, if this repo documents its bundled plugins there —
      check during implementation and skip if there's no existing precedent for
      per-plugin README entries
- [ ] update `CLAUDE.md` if new patterns worth remembering emerged (e.g. the fixed
      `.git/review-comments.json` convention)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification:**
- Visual/UX check of the gutter icon and comment bubble styling in a running Rebased
  build — does it look native, does hover/click feel right, does multi-file "next file"
  navigation in the `review` session feel usable.
- Decide on and add a proper icon for `FinishReviewAction` (currently unscoped — Task 4
  can ship without a custom icon, using a platform default).

**External/deferred items:**
- Optionally stripping `plugins/github` and `plugins/gitlab` from this fork checkout —
  explicitly out of scope for this plan, flagged during research as worth a future
  decision.
- If `ChangesetResolver`'s git plumbing needs are broader than what git4idea's public
  APIs expose cleanly, a follow-up may be needed to either extend those APIs or commit to
  the `git show`/`git diff` shell-out fallback long-term.
