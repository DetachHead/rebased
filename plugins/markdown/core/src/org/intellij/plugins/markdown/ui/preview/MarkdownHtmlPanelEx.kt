package org.intellij.plugins.markdown.ui.preview

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
@ApiStatus.Internal
interface MarkdownHtmlPanelEx: MarkdownHtmlPanel, UserDataHolder {
  fun scrollBy(horizontalUnits: Int, verticalUnits: Int)

  val canScrollToSourceOffset: Boolean
    get() = false

  fun scrollToSourceOffset(offset: Double) {}

  companion object {
    @ApiStatus.Internal
    val DO_NOT_USE_LINK_OPENER = Key<Boolean>("DO_NOT_USE_LINK_OPENER")
  }
}
