// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Contract

/**
 * Public API for setting up parameters for opening a file or navigating in an editor.
 * The request owns all opening parameters. Methods that accept it do not read opening flags from the descriptor.
 * Use [fromDescriptor] to copy those flags before applying overrides.
 */
@ApiStatus.Experimental
class FileEditorOpenRequest private constructor(
  val openMode: FileEditorOpenMode = FileEditorOpenMode.MANAGED,
  val selectAsCurrent: Boolean = true,
  val reuseOpen: Boolean = false,
  val usePreviewTab: Boolean = false,
  val requestFocus: Boolean = false,
  val pin: Boolean = false,
) {
  companion object {
    @JvmStatic
    @Contract(pure = true)
    fun defaults(): FileEditorOpenRequest = FileEditorOpenRequest()

    @JvmStatic
    @Contract(pure = true)
    fun withFocus(requestFocus: Boolean): FileEditorOpenRequest = FileEditorOpenRequest(requestFocus = requestFocus)

    /** Copies opening flags now. The descriptor still supplies the navigation target when the operation runs. */
    @JvmStatic
    @Contract(pure = true)
    fun fromDescriptor(descriptor: FileEditorNavigatable): FileEditorOpenRequest =
      defaults().withReuseOpen(!descriptor.isUseCurrentWindow).withUsePreviewTab(descriptor.isUsePreviewTab)
  }

  private fun copy(
    openMode: FileEditorOpenMode = this.openMode,
    selectAsCurrent: Boolean = this.selectAsCurrent,
    reuseOpen: Boolean = this.reuseOpen,
    usePreviewTab: Boolean = this.usePreviewTab,
    requestFocus: Boolean = this.requestFocus,
    pin: Boolean = this.pin,
  ): FileEditorOpenRequest {
    return FileEditorOpenRequest(
      openMode = openMode,
      selectAsCurrent = selectAsCurrent,
      reuseOpen = reuseOpen,
      usePreviewTab = usePreviewTab,
      requestFocus = requestFocus,
      pin = pin,
    )
  }

  /** Selects the tab and makes its window current. A new window always selects its first tab locally. */
  @Contract(pure = true)
  fun withSelectAsCurrent(value: Boolean): FileEditorOpenRequest = copy(selectAsCurrent = value)

  /** Reuses a composite of the file which is already open. `false` does not require a new editor. */
  @Contract(pure = true)
  fun withReuseOpen(value: Boolean): FileEditorOpenRequest = copy(reuseOpen = value)

  /** Requests a preview tab. `false` does not override the IDE preview settings. */
  @Contract(pure = true)
  fun withUsePreviewTab(value: Boolean): FileEditorOpenRequest = copy(usePreviewTab = value)

  /**
   * Requests focus only when [selectAsCurrent] is `true`.
   * Remote Development may ignore [selectAsCurrent] for a reused tab.
   */
  @Contract(pure = true)
  fun withRequestFocus(value: Boolean): FileEditorOpenRequest = copy(requestFocus = value)

  /** Pins the tab. `false` does not unpin an existing tab. */
  @Contract(pure = true)
  fun withPin(value: Boolean): FileEditorOpenRequest = copy(pin = value)

  /** Sets the placement mode. See [FileEditorManager.requestOpenFile] for [FileEditorOpenMode.MANAGED] semantics. */
  @Contract(pure = true)
  fun withOpenMode(openMode: FileEditorOpenMode): FileEditorOpenRequest = copy(openMode = openMode)

  override fun toString(): String {
    return "FileEditorOpenRequest(" +
           "openMode=$openMode, " +
           "selectAsCurrent=$selectAsCurrent, " +
           "reuseOpen=$reuseOpen, " +
           "usePreviewTab=$usePreviewTab, " +
           "requestFocus=$requestFocus, " +
           "pin=$pin)"
  }
}

/**
 * The placement of an editor.
 */
@ApiStatus.Experimental
enum class FileEditorOpenMode {
  /** Opens the file in a separate editor window. */
  NEW_WINDOW,
  /** Opens the file in a split to the right of the current editor. */
  RIGHT_SPLIT,
  /**
   * Uses the normal window selection and ignores keyboard and mouse modifiers.
   * Use this when opening a generated file after the new-project wizard, regardless of the keys the user holds.
   */
  DEFAULT,
  /**
   * Lets the opening method select the placement.
   * Use this for navigation actions, such as opening search results with a split or new-window shortcut.
   * E.g, Shift-click can open a search result in a new window.
   * This parameter should be gone after IJPL-257405.
   * @see [FileEditorManager.requestOpenFile] for details
   */
  MANAGED
}
