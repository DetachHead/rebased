package com.intellij.platform.ide.nonModalWelcomeScreen.rightTab

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.nonModalWelcomeScreen.NON_MODAL_WELCOME_SCREEN_SETTING_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import javax.swing.JComponent

@ApiStatus.Internal
abstract class WelcomeScreenRightTab(
  val project: Project,
  val contentProvider: WelcomeRightTabContentProvider,
) : Disposable {
  abstract val component: JComponent

  abstract fun getPreferredFocusedComponent(): JComponent

  /**
   * Switches to a custom content view if the content provider supports it.
   */
  abstract fun switchToCustomContent(provider: WelcomeRightCustomTabProvider)

  /**
   * Switches back to the default welcome screen view.
   */
  abstract fun switchToDefaultContent()

  companion object {
    /**
     * Opens the welcome tab, or brings the open one forward.
     *
     * The tab UI is built by the editor of [WelcomeScreenRightTabVirtualFile], so this is the same path the
     * platform takes when it restores the tab from the editor state.
     *
     * @param focusContent whether the input focus moves into the tab content. Pass `false` (default) for the
     * passive startup open, so the left project view's recent-projects search field keeps the focus (accessibility,
     * IJPL-248588); pass `true` for a user-initiated open (the "Open Welcome Screen" action).
     */
    @ApiStatus.Internal
    suspend fun show(project: Project, focusContent: Boolean = false) {
      if (!isRightTabEnabled) return
      val file = WelcomeScreenRightTabVirtualFile.getInstance() ?: return
      val focusState = WelcomeScreenTabFocusState.getInstanceAsync(project)
      if (focusContent) {
        focusState.enableContentFocus()
      }
      val fileEditorManager = project.serviceAsync<FileEditorManager>() as FileEditorManagerEx
      withContext(Dispatchers.EDT) {
        val options = FileEditorOpenOptions(
          reuseOpen = true,
          forceFocus = focusContent,
          requestFocus = focusContent,
          selectAsCurrent = focusContent || focusState.selectsTabOnStartupOpen,
        )
        fileEditorManager.openFile(file, options)
      }
    }

    @JvmStatic
    internal var isRightTabEnabled: Boolean
      get() = AdvancedSettings.getBoolean(NON_MODAL_WELCOME_SCREEN_SETTING_ID)
      set(value) = AdvancedSettings.setBoolean(NON_MODAL_WELCOME_SCREEN_SETTING_ID, value)
  }
}