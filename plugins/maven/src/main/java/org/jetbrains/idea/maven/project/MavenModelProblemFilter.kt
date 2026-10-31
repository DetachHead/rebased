// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.model.MavenId
import java.util.regex.Pattern

/**
 * Makes Maven model-problem locations navigable in consoles and the built-in terminal (IDEA-387177).
 *
 * Maven prints model validation problems (missing plugin version, deprecated expressions, ...) as:
 * ```
 * [WARNING] Some problems were encountered while building the effective model for org.example:app:jar:1.0
 * [WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-jar-plugin is missing. @ line 20, column 15
 * ```
 *
 * The trailing `@ [<modelId>, <source>, ]line <n>[, column <n>]` suffix (produced by
 * `org.apache.maven.model.building.ModelProblemUtils#formatLocation`) points at a location inside a
 * `pom.xml`. This filter turns that suffix into a hyperlink that opens the owning `pom.xml` at the
 * reported line/column.
 *
 * For "own pom" problems Maven omits the model id and source from the location, so the filter keeps
 * track of the most recently reported "effective model" header to know which module the location
 * belongs to. Fresh instances are created per console/terminal by [MavenConsoleFilterProvider] and
 * receive lines in order, which makes the [currentModelId] state safe.
 */
class MavenModelProblemFilter(private val project: Project) : Filter {

  @Volatile
  private var currentModelId: String? = null

  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val headerMatcher = MODEL_HEADER_PATTERN.matcher(line)
    if (headerMatcher.find()) {
      currentModelId = headerMatcher.group(1)
      return null
    }

    val matcher = PROBLEM_LOCATION_PATTERN.matcher(line)
    if (!matcher.find()) return null

    val lineNumber = matcher.group(2)?.toIntOrNull() ?: return null
    val columnNumber = matcher.group(3)?.toIntOrNull()

    val prefix = matcher.group(1)?.trim().orEmpty()
    val fields = prefix.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
    val modelId: String?
    val sourcePath: String?
    when (fields.size) {
      0 -> {
        modelId = currentModelId
        sourcePath = null
      }
      1 -> {
        modelId = fields[0]
        sourcePath = null
      }
      else -> {
        modelId = fields[0]
        sourcePath = fields[1]
      }
    }

    val pom = resolvePom(modelId, sourcePath) ?: return null

    val lineStart = entireLength - line.length
    val highlightStart = lineStart + matcher.start()
    val highlightEnd = lineStart + if (columnNumber != null) matcher.end(3) else matcher.end(2)

    val hyperlink: HyperlinkInfo = OpenFileHyperlinkInfo(
      project, pom,
      (lineNumber - 1).coerceAtLeast(0),
      ((columnNumber ?: 1) - 1).coerceAtLeast(0)
    )
    return Filter.Result(highlightStart, highlightEnd, hyperlink)
  }

  private fun resolvePom(modelId: String?, sourcePath: String?): VirtualFile? {
    if (sourcePath != null) {
      val bySource = LocalFileSystem.getInstance().findFileByPath(sourcePath)
      if (bySource != null && !bySource.isDirectory) return bySource
    }
    val mavenId = modelId?.let(::parseMavenId) ?: return null
    val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return null
    manager.findProject(mavenId)?.let { return it.file }
    manager.findSingleProjectInReactor(mavenId)?.let { return it.file }
    return manager.projects.firstOrNull {
      !it.isNew &&
      it.mavenId.groupId == mavenId.groupId &&
      it.mavenId.artifactId == mavenId.artifactId
    }?.file
  }

  private fun parseMavenId(coordinates: String): MavenId? {
    val parts = coordinates.split(":")
    if (parts.size < 3) return null
    return MavenId(parts[0], parts[1], parts.last())
  }

  companion object {
    private val MODEL_HEADER_PATTERN: Pattern =
      Pattern.compile("building the effective model for (\\S+)")
    private val PROBLEM_LOCATION_PATTERN: Pattern =
      Pattern.compile("@ (?:([^@]*?), )?line (\\d+)(?:, column (\\d+))?\\s*$")
  }
}
