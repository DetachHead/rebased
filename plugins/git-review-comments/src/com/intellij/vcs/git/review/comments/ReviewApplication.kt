// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.ide.CliResult
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.project.ProjectManager
import java.io.File

/**
 * `review` CLI command: opens a **multi-file** diff review session for a git changeset,
 * unlike `diff`/[com.intellij.diff.applications.DiffApplication], which only ever compares
 * exactly two file paths.
 *
 * Accepts zero or one argument: no argument reviews uncommitted changes, one argument is a
 * ref/range handed straight to [ChangesetResolver] (see its class doc for the accepted
 * shapes). [openReviewSession] builds the actual diff chain and wires up the comment UI --
 * the same comment UI ([ReviewDiffExtension]) also applies automatically to any other diff of
 * a local/uncommitted change (Local Changes view, "Show Diff", etc.), with no CLI invocation
 * needed at all -- see the class doc on [ReviewDiffExtension].
 */
internal class ReviewApplication : ApplicationStarterBase(/* possibleArgumentsCount = */ 0, 1, 2) {
  override val commandName: String get() = "review"
  override val usageMessage: String
    get() = GitReviewCommentsBundle.message("review.application.usage")

  override suspend fun executeCommand(args: List<String>, currentDirectory: String?): CliResult {
    val ref = parseRef(args)
    val repoRoot = resolveRepoRoot(currentDirectory)
    val project = ProjectManager.getInstance().openProjects.firstOrNull()
    openReviewSession(project, repoRoot, ref)
    return CliResult.OK
  }

  companion object {
    /**
     * `args[0]` is always the command name itself (`"review"`), matching
     * `DiffApplication.executeCommand`'s own `args.drop(1)` convention -- so `args.size == 1`
     * means "no ref given" (uncommitted changes), `args.size == 2` means one ref/range
     * argument, and `args.size == 3` means two separate ref arguments (e.g. an unquoted
     * `rebased review main feature` invocation, two distinct shell tokens) -- joined back into
     * one space-separated string here so [ChangesetResolver.splitRef]'s existing
     * whitespace-splitting handles both this case and a single quoted `"main feature"`
     * argument identically. Internal (not private) so [ReviewApplicationTest] can exercise
     * this without touching the diff-opening side effect.
     */
    internal fun parseRef(args: List<String>): String? {
      val refArgs = args.drop(1)
      return if (refArgs.isEmpty()) null else refArgs.joinToString(" ")
    }

    /**
     * Resolves the actual git repository root for a `review` invocation, via
     * `git rev-parse --show-toplevel` run from [currentDirectory] (or the JVM's working
     * directory if the CLI didn't supply one) -- rather than treating that directory itself
     * as the repo root, which breaks every repo-relative path resolved by
     * [ChangesetResolver]/[ReviewCommentsJsonExporter] whenever `review` is invoked from a
     * subdirectory of the repo. Internal (not private), and takes a [git] factory, so
     * [ReviewApplicationTest] can exercise this against a fake [GitCommandRunner] rather than
     * a real git checkout.
     *
     * @throws GitCommandException if [currentDirectory] is not inside a git repository
     */
    internal fun resolveRepoRoot(currentDirectory: String?, git: (File) -> GitCommandRunner = ::ProcessGitCommandRunner): File {
      val cwd = File(currentDirectory ?: System.getProperty("user.dir"))
      val output = git(cwd).run("rev-parse", "--show-toplevel")
      return File(output.trim())
    }
  }
}
