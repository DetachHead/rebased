// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.nonModalWelcomeScreen

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.DefaultProjectFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeRightTabContentProvider
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeScreenRightTabVirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.util.ui.EmptyIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Image
import java.awt.image.BufferedImage
import java.util.function.Supplier
import javax.swing.Icon

/**
 * The platform restores a persisted editor tab by URL, so the welcome tab file must resolve through the
 * `VirtualFileManager` to the one instance the tab was opened with.
 */
@TestApplication
internal class WelcomeScreenRightTabFileSystemTest {
  @TestDisposable
  lateinit var disposable: Disposable

  @Test
  fun `the welcome tab file resolves by url to the one instance`() {
    maskContentProviders(listOf(TestContentProvider()))

    val file = checkNotNull(WelcomeScreenRightTabVirtualFile.getInstance())
    assertEquals("welcome-screen://welcome", file.url)

    val virtualFileManager = VirtualFileManager.getInstance()
    assertSame(file, virtualFileManager.findFileByUrl(file.url))
    assertSame(file, virtualFileManager.refreshAndFindFileByUrl(file.url))
    val fileAgain = WelcomeScreenRightTabVirtualFile.getInstance()
    assertSame(file, fileAgain)
    assertNull(virtualFileManager.findFileByUrl("welcome-screen://other"))
  }

  @Test
  fun `the welcome tab stays out of the editor history`() {
    maskContentProviders(listOf(TestContentProvider()))

    val file = checkNotNull(WelcomeScreenRightTabVirtualFile.getInstance())
    assertFalse(file.isIncludedInEditorHistory(DefaultProjectFactory.getInstance().defaultProject))
    assertFalse(file.isWritable)
  }

  @Test
  fun `without a content provider there is no welcome tab file`() {
    maskContentProviders(emptyList())

    assertNull(WelcomeScreenRightTabVirtualFile.getInstance())
    assertNull(VirtualFileManager.getInstance().findFileByUrl("welcome-screen://welcome"))
  }

  private fun maskContentProviders(providers: List<WelcomeRightTabContentProvider>) {
    ExtensionTestUtil.maskExtensions(
      ExtensionPointName("com.intellij.platform.ide.welcomeScreenContentProvider"),
      providers,
      disposable,
    )
  }

  private class TestContentProvider : WelcomeRightTabContentProvider {
    @OptIn(DelicateCoroutinesApi::class)
    override val coroutineScope: CoroutineScope = GlobalScope
    override val backgroundImageVectorLight: Image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    override val backgroundImageVectorDark: Image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    override val fileTypeIcon: Icon = EmptyIcon.ICON_16
    override val title: Supplier<String> = Supplier { "Welcome" }
    override val secondaryTitle: Supplier<String> = Supplier { "" }
    override val isDisableOptionVisible: Boolean = false

    override fun getFeatureButtonModels(project: Project): List<WelcomeRightTabContentProvider.FeatureButtonModel> = emptyList()
  }
}
