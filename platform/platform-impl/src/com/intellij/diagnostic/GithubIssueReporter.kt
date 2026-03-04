package com.intellij.diagnostic

import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import org.jetbrains.annotations.ApiStatus

import com.intellij.ide.BrowserUtil

import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.platform.ide.customization.ExternalProductResourceUrls
import com.intellij.util.Consumer
import java.awt.Component

/**
 * used to prevent errors from URLs being too big. this isn't the exact limit just a ballpark
 */
private const val maxDescriptionLength = 6000

open class GithubIssueReporter internal constructor() : ErrorReportSubmitter() {
  override fun getReportActionText(): String = DiagnosticBundle.message("error.report.to.github.action")

  override fun getPrivacyNoticeText(): String = DiagnosticBundle.message("error.dialog.notice.github")

  @ApiStatus.OverrideOnly
  override fun submit(
    events: Array<IdeaLoggingEvent>,
    additionalInfo: String?,
    parentComponent: Component,
    consumer: Consumer<in SubmittedReportInfo>,
  ): Boolean {
    // determine how much of each stack trace to show based on how many there are
    val maxStackTraceLength = (maxDescriptionLength - (additionalInfo?.length ?: 0) / events.size)
    // this looks gross as hell, but apparently trimIndent doesn't work with interpolation, and github is very particular about the newlines
    // necessary for details blocks to work...
    val errorDescriptions = events.joinToString("\n") {
"""```
${it.throwableText.take((maxStackTraceLength))}
```"""
    }
    val description =
"""${additionalInfo ?: ""}
<details><summary>Exceptions</summary><p>

$errorDescriptions
</p></details>"""
    BrowserUtil.open(ExternalProductResourceUrls.getInstance().bugReportUrl!!(description).toString())
    consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
    return true
  }
}
