package com.intellij.idea.customization.base

import com.intellij.platform.ide.impl.customization.BaseJetBrainsExternalProductResourceUrls
import com.intellij.util.Url
import com.intellij.util.Urls

internal class RebasedExternalResourceUrls : IntelliJIdeaExternalResourceUrls() {
  override val basePatchDownloadUrl: Url
    get() {
      throw NotImplementedError()
    }

  override val productPageUrl: Url
    get() = Urls.newFromEncoded("https://github.com/detachhead/rebased")

  override val bugReportUrl
    get() = { description: String ->
      productPageUrl.resolve("issues/new").addParameters(mapOf("body" to description, "labels" to "IDE internal error"))
    }

  override val youtrackProjectId: String
    get() {
      throw NotImplementedError()
    }

  override val shortProductNameUsedInForms
    get() = null

  override val useInIdeGeneralFeedback: Boolean
    get() = false

  override val useInIdeEvaluationFeedback: Boolean
    get() = false

  override val youTubeChannelUrl
    get() = null

  override val keyboardShortcutsPdfUrl
    get() = null

  override val gettingStartedPageUrl
    get() = null
}