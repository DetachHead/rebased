package org.intellij.plugins.markdown

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import org.intellij.plugins.markdown.extensions.jcef.commandRunner.RunnerPlace
import org.intellij.plugins.markdown.extensions.jcef.commandRunner.RunnerType
import org.intellij.plugins.markdown.ui.preview.MarkdownEditorWithPreview
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object MarkdownUsageCollector : CounterUsagesCollector() {
  private val LOG = thisLogger()
  private val GROUP = EventLogGroup("markdown.events", 3)

  private enum class EditorLayout {
    EDITOR,
    EDITOR_AND_PREVIEW,
    PREVIEW,
    LIVE_PREVIEW,
  }

  val RUNNER_EXECUTED = GROUP.registerEvent(
    "runner.executed",
    EventFields.Enum("location", RunnerPlace::class.java),
    EventFields.Enum("type", RunnerType::class.java),
    EventFields.Class("runner")
  )

  private val EDITOR_LAYOUT_CHANGED = GROUP.registerEvent(
    "editor.layout.changed",
    EventFields.Enum("layout", EditorLayout::class.java),
  )

  @JvmStatic
  fun logEditorLayoutChanged(editor: MarkdownEditorWithPreview) {
    val layout = getEditorLayout(editor) ?: return
    EDITOR_LAYOUT_CHANGED.log(layout)
  }

  private fun getEditorLayout(editor: MarkdownEditorWithPreview): EditorLayout? {
    if (editor.isLivePreviewLayout) {
      return EditorLayout.LIVE_PREVIEW
    }

    val layout = editor.getLayout()
    if (layout == null) {
      LOG.error("Markdown's layout is null")
    }
    return when (layout) {
      TextEditorWithPreview.Layout.SHOW_EDITOR -> EditorLayout.EDITOR
      TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW -> EditorLayout.EDITOR_AND_PREVIEW
      TextEditorWithPreview.Layout.SHOW_PREVIEW -> EditorLayout.PREVIEW
      null -> null
    }
  }

  override fun getGroup(): EventLogGroup {
    return GROUP
  }
}