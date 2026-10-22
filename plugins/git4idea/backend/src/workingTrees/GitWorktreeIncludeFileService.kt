// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.i18n.GitBundle
import git4idea.index.NUL
import git4idea.repo.GitRepository
import java.io.IOException
import java.nio.file.Path

internal const val WORKTREE_INCLUDE_FILE_NAME: String = ".worktreeinclude"

/**
 * File I/O for the New Worktree dialog's "Copy project config" section: creating and editing a repository's
 * `.worktreeinclude` file, plus the idea-settings AI-agent skill file that travels with it. Kept separate from
 * [git4idea.workingTrees.dialog.GitWorktreeConfigCategoriesPanel], which only builds and wires up the UI.
 */
@Service(Service.Level.PROJECT)
internal class GitWorktreeIncludeFileService {

  companion object {
    private val LOG = logger<GitWorktreeIncludeFileService>()

    /**
     * Marks the idea-settings block this file generates. [hasIdeaSettingsSection] looks for this exact text,
     * so this must not be localized: a locale-dependent marker would make "does this file already have the
     * section" locale-dependent too, and could silently duplicate the section.
     */
    private const val IDEA_SETTINGS_SECTION_MARKER: String = "# --- IntelliJ project settings, added automatically ---"

    /** Directory, relative to the repository root, that holds [IDEA_SETTINGS_SKILL_FILE_NAME] for this project. */
    internal const val IDEA_SETTINGS_SKILL_DIR_RELATIVE_PATH: String = ".claude/skills/worktree-idea-settings"

    /** Name of the AI-agent skill file [ideaSettingsSkillFileContent] generates. */
    internal const val IDEA_SETTINGS_SKILL_FILE_NAME: String = "SKILL.md"

    /** Classloader path of [ideaSettingsSkillFileContent]'s resource, relative to this module's resource root. */
    private const val IDEA_SETTINGS_SKILL_RESOURCE_PATH: String = "worktree/worktree-idea-settings-skill.md"

    /**
     * Name patterns for files that are typically local overrides, never meant to be committed. The list
     * includes [WORKTREE_INCLUDE_FILE_NAME] itself. An untracked .worktreeinclude file then matches its own
     * pattern, so [findWorktreeIncludeMatches] and the copy pipeline propagate it into a freshly created
     * worktree too.
     */
    private val LOCAL_OVERRIDE_NAME_PATTERNS = listOf(
      WORKTREE_INCLUDE_FILE_NAME,
      ".env", ".env.local", ".env.*.local",
      "local.properties", "*.local.properties",
      "docker-compose.override.yml", "docker-compose.override.yaml",
      "*.local.yml", "*.local.yaml", "*.local.json", "*.local.xml",
      "secrets.yml", "secrets.yaml",
    )

    private val IDEA_SETTINGS_NAME_PATTERNS = listOf(
      ".idea/*.xml",
      ".idea/runConfigurations/",
      ".idea/codeStyles/",
      ".idea/inspectionProfiles/",
      ".run/",
      "$IDEA_SETTINGS_SKILL_DIR_RELATIVE_PATH/",
    )

    fun getInstance(project: Project): GitWorktreeIncludeFileService =
      project.getService(GitWorktreeIncludeFileService::class.java)

    /**
     * Returns every path under [root] that matches a pattern in [worktreeIncludeFile], tracked or not. This
     * reuses git's own gitignore-syntax engine instead of a hand-rolled matcher, so a negated pattern (`!foo`)
     * behaves exactly like it does in a real `.gitignore`.
     */
    internal fun findWorktreeIncludeMatches(project: Project, root: VirtualFile, worktreeIncludeFile: Path): List<Path> {
      require(worktreeIncludeFile == root.toNioPath().resolve(WORKTREE_INCLUDE_FILE_NAME)) {
        "worktreeIncludeFile must be $WORKTREE_INCLUDE_FILE_NAME directly under root, got $worktreeIncludeFile"
      }

      val handler = GitLineHandler(project, root, GitCommand.LS_FILES)
      handler.setSilent(true)
      // --exclude-from takes a plain command-line argument, which is not converted for a remote Eel environment
      // the way the handler's own executable and working directory are. Passing the file name relative to that
      // working directory (already `root`) avoids the need for a host-to-target path conversion here.
      handler.addParameters("-z", "--cached", "--others", "--ignored", "--exclude-from=$WORKTREE_INCLUDE_FILE_NAME")
      handler.endOptions()

      val output = Git.getInstance().runCommand(handler).getOutputOrThrow()
      if (output.isBlank()) return emptyList()

      val rootPath = root.toNioPath()
      return output.split(NUL).filter { it.isNotEmpty() }.map { rootPath.resolve(it) }
    }

    internal fun generateWorktreeIncludeContent(includeIdeaSettings: Boolean): String {
      val content = GitBundle.message("working.tree.dialog.worktree.include.header.comment") + "\n" +
        LOCAL_OVERRIDE_NAME_PATTERNS.joinToString("\n") + "\n"
      return if (includeIdeaSettings) content + ideaSettingsSection() else content
    }

    /** Returns `true` when [content] already has the idea-settings block [generateWorktreeIncludeContent] adds. */
    internal fun hasIdeaSettingsSection(content: String): Boolean = content.contains(IDEA_SETTINGS_SECTION_MARKER)

    /** Appends the idea-settings block to [content], unless [hasIdeaSettingsSection] is already `true`. */
    internal fun appendIdeaSettingsSection(content: String): String {
      if (hasIdeaSettingsSection(content)) return content
      val separator = if (content.isEmpty() || content.endsWith("\n")) "" else "\n"
      return content + separator + ideaSettingsSection()
    }

    private fun ideaSettingsSection(): String =
      "$IDEA_SETTINGS_SECTION_MARKER\n" +
      GitBundle.message("working.tree.dialog.worktree.include.idea.settings.comment") + "\n" +
      IDEA_SETTINGS_NAME_PATTERNS.joinToString("\n") + "\n"

    /**
     * Content of the AI-agent skill file this project gets under [IDEA_SETTINGS_SKILL_DIR_RELATIVE_PATH] once
     * its `.worktreeinclude` file includes idea settings.
     */
    internal fun ideaSettingsSkillFileContent(): String {
      val resourceLoader = GitWorktreeIncludeFileService::class.java.classLoader
      val stream = resourceLoader.getResourceAsStream(IDEA_SETTINGS_SKILL_RESOURCE_PATH)
                   ?: error("Missing resource $IDEA_SETTINGS_SKILL_RESOURCE_PATH")
      return stream.bufferedReader().use { it.readText() }
    }
  }

  /**
   * Writes a new `.worktreeinclude` file with [content] under [repository]'s root. Also writes this project's
   * own copy of the idea-settings AI-agent skill file when [includeIdeaSettings] is `true`, so an agent working
   * in this project can find and follow the manual copy procedure. Returns `true` on success; the file always
   * lands at the fixed [WORKTREE_INCLUDE_FILE_NAME] path under [repository]'s root, so the caller already knows it.
   */
  fun writeWorktreeIncludeFile(repository: GitRepository, content: String, includeIdeaSettings: Boolean): Boolean {
    val root = repository.root
    val fileContent = GitBundle.message("working.tree.dialog.worktree.include.close.comment") + "\n" + content
    return try {
      runWriteAction {
        val newFile = root.createChildData(root, WORKTREE_INCLUDE_FILE_NAME)
        VfsUtil.saveText(newFile, fileContent)
        if (includeIdeaSettings) ensureIdeaSettingsSkillFile(root)
      }
      true
    }
    catch (e: IOException) {
      LOG.warn("Failed to create $WORKTREE_INCLUDE_FILE_NAME under ${root.path}", e)
      false
    }
  }

  /**
   * Appends the idea-settings block to [repository]'s existing `.worktreeinclude` file. Returns `true` on
   * success, and `false` both when the file does not exist and when the update fails.
   */
  fun addIdeaSettingsSection(repository: GitRepository): Boolean {
    val root = repository.root
    val file = root.findChild(WORKTREE_INCLUDE_FILE_NAME) ?: return false
    return try {
      val updatedContent = appendIdeaSettingsSection(VfsUtil.loadText(file))
      runWriteAction {
        VfsUtil.saveText(file, updatedContent)
        ensureIdeaSettingsSkillFile(root)
      }
      true
    }
    catch (e: IOException) {
      LOG.warn("Failed to update $WORKTREE_INCLUDE_FILE_NAME under ${root.path}", e)
      false
    }
  }

  /**
   * Writes this project's own idea-settings AI-agent skill file under [IDEA_SETTINGS_SKILL_DIR_RELATIVE_PATH],
   * relative to [root], unless one is already there. Distinct from, and in addition to, any personal skill an
   * agent's own user may have: this copy travels with the project, so any agent working in it can find it.
   */
  private fun ensureIdeaSettingsSkillFile(root: VirtualFile) {
    val skillDir = VfsUtil.createDirectoryIfMissing(root, IDEA_SETTINGS_SKILL_DIR_RELATIVE_PATH)
    if (skillDir.findChild(IDEA_SETTINGS_SKILL_FILE_NAME) != null) return
    val skillFile = skillDir.createChildData(root, IDEA_SETTINGS_SKILL_FILE_NAME)
    VfsUtil.saveText(skillFile, ideaSettingsSkillFileContent())
  }
}
