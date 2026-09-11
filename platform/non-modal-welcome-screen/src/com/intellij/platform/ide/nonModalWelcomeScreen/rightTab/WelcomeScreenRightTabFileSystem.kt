// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen.rightTab

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem

internal const val WELCOME_SCREEN_TAB_PROTOCOL: String = "welcome-screen"

/** The whole path. One welcome tab exists per application, so the URL carries no identity of its own. */
internal const val WELCOME_SCREEN_TAB_PATH: String = "welcome"

/**
 * Resolves the one welcome tab file, on a cold restore too.
 *
 * The platform reopens a persisted editor tab by its URL, so [findFileByPath] is the whole restore path of the
 * welcome tab. The file is identity only. The editor that the platform creates for it builds the tab UI.
 */
internal class WelcomeScreenRightTabFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
  private val lock = Any()

  /** The file and the content provider it was built from. A reloaded provider gets a new file. */
  private var cached: CachedFile? = null

  override fun getProtocol(): String = WELCOME_SCREEN_TAB_PROTOCOL

  override fun findFileByPath(path: String): VirtualFile? = if (path == WELCOME_SCREEN_TAB_PATH) getFile() else null

  override fun refresh(asynchronous: Boolean) = Unit

  override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

  /** The welcome tab file, or `null` when the product installs no [WelcomeRightTabContentProvider]. */
  fun getFile(): WelcomeScreenRightTabVirtualFile? {
    val provider = WelcomeRightTabContentProvider.getSingleExtension() ?: return null
    synchronized(lock) {
      cached?.takeIf { it.provider === provider }?.let { return it.file }
      val file = WelcomeScreenRightTabVirtualFile(fileSystem = this, contentProvider = provider)
      cached = CachedFile(provider, file)
      return file
    }
  }

  private class CachedFile(
    @JvmField val provider: WelcomeRightTabContentProvider,
    @JvmField val file: WelcomeScreenRightTabVirtualFile,
  )
}

internal fun welcomeScreenRightTabFileSystem(): WelcomeScreenRightTabFileSystem {
  val fileSystem = VirtualFileSystem.EP_NAME.extensionList.firstOrNull { it.key == WELCOME_SCREEN_TAB_PROTOCOL }?.instance
  return fileSystem as? WelcomeScreenRightTabFileSystem
         ?: error("WelcomeScreenRightTabFileSystem is not registered for protocol $WELCOME_SCREEN_TAB_PROTOCOL")
}
