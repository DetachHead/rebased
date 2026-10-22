// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.dialog

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.validation.WHEN_GRAPH_PROPAGATION_FINISHED
import com.intellij.ui.components.Badge
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.layout.ValidationInfoBuilder
import git4idea.i18n.GitBundle
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** The "Run setup script" section of the New Worktree dialog. Call [buildPanel] once to add its rows. */
internal class GitWorktreeSetupScriptPanel(
  private val propertyGraph: PropertyGraph,
  savedState: GitWorkingTreeDialogState?,
  private val project: Project,
) {
  val runSetupScript: GraphProperty<Boolean> = propertyGraph.property(savedState?.runSetupScript ?: false)
  val setupScriptPath: GraphProperty<String> = propertyGraph.property(savedState?.setupScriptPath ?: "")

  fun buildPanel(panel: Panel) = with(panel) {
    row {
      checkBox(GitBundle.message("working.tree.dialog.checkbox.setup.script"))
        .bindSelected(runSetupScript)
        .gap(RightGap.SMALL)
      icon(Badge.beta)
    }
    row(GitBundle.message("working.tree.dialog.label.setup.script")) {
      val descriptor = FileChooserDescriptorFactory.singleFile()
        .withTitle(GitBundle.message("working.tree.dialog.label.setup.script.file.chooser.title"))
      textFieldWithBrowseButton(descriptor, project).apply {
        // TextFieldWithBrowseButton is not ErrorBorderCapable, so the validation outline must target
        // the inner text field, not the compound component, or the red border never paints.
        component.putClientProperty(DslComponentProperty.INTERACTIVE_COMPONENT, component.textField)
      }
        .bindText(setupScriptPath)
        .align(Align.FILL)
        .validationRequestor(WHEN_GRAPH_PROPAGATION_FINISHED(propertyGraph))
        .validationOnInput {
          if (setupScriptPath.get().isBlank()) return@validationOnInput null
          validateSetupScriptPath()
        }
        .validationOnApply { validateSetupScriptPath() }
        .comment(GitBundle.message("working.tree.dialog.setup.script.comment"), maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
    }.apply {
      visible(runSetupScript.get())
      runSetupScript.afterChange { visible(it) }
    }
  }

  private fun ValidationInfoBuilder.validateSetupScriptPath(): ValidationInfo? {
    if (!runSetupScript.get()) return null
    if (setupScriptPath.get().isBlank()) {
      return error(GitBundle.message("working.tree.dialog.setup.script.validation.empty"))
    }
    val path = parseAbsolutePath(setupScriptPath.get())
               ?: return error(GitBundle.message("working.tree.dialog.setup.script.validation.not.absolute"))
    if (!path.isRegularFile()) {
      return error(GitBundle.message("working.tree.dialog.setup.script.validation.not.a.file"))
    }
    return null
  }

  /** Returns the validated, absolute setup-script path, or `null` when the script is off or the path fails validation. */
  fun getValidatedScriptPath(): Path? {
    if (!runSetupScript.get()) return null
    return parseAbsolutePath(setupScriptPath.get())?.takeIf { it.isRegularFile() }
  }

  /** Parses [text] as a path. Returns `null` for a malformed or a relative path. */
  private fun parseAbsolutePath(text: String): Path? {
    val path = try {
      Path.of(text)
    }
    catch (_: InvalidPathException) {
      return null
    }
    return path.takeIf { it.isAbsolute }
  }
}
