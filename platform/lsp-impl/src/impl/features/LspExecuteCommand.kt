package com.intellij.platform.lsp.impl.features

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.platform.lsp.impl.LspClientImpl
import com.intellij.platform.lsp.impl.LspCoroutineScopeService
import com.intellij.util.concurrency.Semaphore
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Sends a [workspace/executeCommand](https://microsoft.github.io/language-server-protocol/specification/#workspace_executeCommand)
 * request to the server and waits for whichever comes first:
 *  - a `workspace/applyEdit` request from the server - in this case this function returns the corresponding [WorkspaceEdit],
 *  and it is up to the caller to apply it;
 *  - a response to the `executeCommand` request (any response, including an error) - in this case this function returns `null`.
 *
 * Waiting is cancellable, so this function is safe to call inside a cancellable read action.
 */
@RequiresBackgroundThread
internal fun LspClientImpl.executeCommandExpectingWorkspaceEdit(command: Command): WorkspaceEdit? {
  // Released as soon as either the server sends a `workspace/applyEdit` request or the `executeCommand` request gets a response.
  val semaphore = Semaphore(1)
  var workspaceEdit: WorkspaceEdit? = null

  try {
    serverNotificationsHandler.nextApplyEditHandler = { edit ->
      workspaceEdit = edit
      semaphore.up()
    }

    LspCoroutineScopeService.getInstance(project).cs.launch {
      try {
        sendRequest { it.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)) }
      }
      finally {
        semaphore.up()
      }
    }

    @Suppress("UsagesOfObsoleteApi")
    ProgressIndicatorUtils.awaitWithCheckCanceled(semaphore, ProgressManager.getInstance().progressIndicator)
  }
  finally {
    serverNotificationsHandler.nextApplyEditHandler = null
  }

  return workspaceEdit
}
