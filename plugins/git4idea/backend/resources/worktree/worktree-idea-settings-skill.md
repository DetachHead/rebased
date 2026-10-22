---
name: worktree-idea-settings
description: Copy local .idea settings into a git worktree of this project, and clean them up after the copy.
---

# Worktree idea settings

## When this applies

Only after a user explicitly asks for a new git worktree of this project. Do not create a worktree, a
clone, or another workspace-isolation mechanism on your own initiative.

## What to copy

Run this command from the source repository root. Copy every match into the new worktree. Never overwrite
a file already there.

```bash
git ls-files -z --cached --others --ignored --exclude-from=.worktreeinclude
```

The `.worktreeinclude` file at the repository root is a gitignore-style pattern list. This command reports
only local files: untracked, ignored, or staged but never committed. A file already committed to git is
not local. The new worktree already has it from the commit it was created at, so do not copy it.

## Required post-copy cleanup

Do this for every copied file under `.idea/` or `.run/` with a `.xml` or `.iml` extension.

- **Remap an absolute path.** A copied file can hold the old worktree's absolute path, for example in a
  `WORKING_DIRECTORY` option value, or embedded inside a larger string such as JSON. Rewrite each
  occurrence to the same relative location under the new worktree's root.
- **Strip `ProjectId`, only in `.idea/workspace.xml`.** Remove the `<component name="ProjectId" .../>`
  element if it is present. A new worktree needs its own project identity. The source project's identity
  would make the IDE treat the two projects as one.

Do not remove anything else from `workspace.xml`. Other stale state, such as recent-file lists or window
bounds, is cosmetic. It does not break the new project.

## What NOT to do

- Do not copy the whole `.idea` directory. Only copy what `.worktreeinclude` matches.
- Do not copy a file already committed to git.
- Do not touch a file outside `.idea` or `.run`.
- Do not overwrite a file already present at the target path.
