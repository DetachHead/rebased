// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.editorconfig.configmanagement

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManagerImpl
import com.intellij.openapi.vfs.encoding.EncodingProjectManagerImpl.BOMForNewUTF8Files
import com.intellij.testFramework.TemporaryDirectory.Companion.generateTemporaryPath
import org.editorconfig.Utils
import org.editorconfig.configmanagement.EditorConfigEncodingCache.Companion.getInstance
import org.junit.Assert
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class EditorConfigEncodingTest : EditorConfigFileSettingsTestCase() {
  override fun tearDown() {
    try {
      getInstance().reset()
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  fun testUtf8Bom() {
    val newFile = createTargetFile()
    Assert.assertArrayEquals(CharsetToolkit.UTF8_BOM, newFile.bom)
  }

  fun testOverridden() {
    val newFile = createTargetFile()
    val charset = EncodingManager.getInstance().getEncoding(newFile, true)
    assertEquals(StandardCharsets.ISO_8859_1, charset)
  }

  fun testForcedUtf8() {
    val newFile = createTargetFile()
    val dir = newFile.parent
    val editorConfig = dir.findChild(Utils.EDITOR_CONFIG_FILE_NAME)
    assertNotNull(editorConfig)
    val charset = editorConfig!!.charset
    assertEquals(StandardCharsets.UTF_8, charset)
  }

  // IDEA-317486
  fun testSpaceAndCommentAfterCharset() {
    val newFile = createTargetFile()
    val charset = EncodingManager.getInstance().getEncoding(newFile, true)
    assertEquals(StandardCharsets.ISO_8859_1, charset)
  }

  fun testUtf8OverridesProjectBom() = withProjectBom(BOMForNewUTF8Files.ALWAYS) {
    val newFile = createNewFile("test.txt")
    assertNull(newFile.bom)
    assertSavedBytes(newFile, null)
  }

  fun testUtf8BomOverridesProjectNoBom() = withProjectBom(BOMForNewUTF8Files.NEVER) {
    val newFile = createNewFile("test.txt")
    Assert.assertArrayEquals(CharsetToolkit.UTF8_BOM, newFile.bom)
    assertSavedBytes(newFile, CharsetToolkit.UTF8_BOM)
  }

  fun testUnsetCharsetUsesProjectBom() = withProjectBom(BOMForNewUTF8Files.ALWAYS) {
    Assert.assertArrayEquals(CharsetToolkit.UTF8_BOM, createNewFile("test.txt").bom)
  }

  fun testInvalidCharsetUsesProjectBom() = withProjectBom(BOMForNewUTF8Files.ALWAYS) {
    Assert.assertArrayEquals(CharsetToolkit.UTF8_BOM, createNewFile("test.txt").bom)
  }

  fun testDisabledEditorConfigUsesProjectBom() = withProjectBom(BOMForNewUTF8Files.ALWAYS) {
    Utils.isEnabledInTests = false
    Assert.assertArrayEquals(CharsetToolkit.UTF8_BOM, createNewFile("test.txt").bom)
  }

  fun testNonUtf8CharsetGetsNoBom() = withProjectBom(BOMForNewUTF8Files.ALWAYS) {
    assertNull(createNewFile("test.txt").bom)
  }

  @Throws(IOException::class)
  private fun createTargetFile(): VirtualFile {
    val dir = generateTemporaryPath(getTestName(true))
    Files.createDirectories(dir)
    Files.copy(testDataPath.resolve(".editorconfig"), dir.resolve(".editorconfig"))
    val targetDir = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(dir)
    val file = runWriteAction { targetDir!!.createChildData(this, "test.txt") }
    getInstance().computeAndCacheEncoding(project, file)
    return file
  }

  private fun createNewFile(name: String, relativeDir: String = ""): VirtualFile {
    val dir = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(testDataPath.resolve(relativeDir))!!
    return runWriteAction { dir.createChildData(this, name) }
  }

  private fun assertSavedBytes(file: VirtualFile, expectedBom: ByteArray?) {
    val text = "text"
    runWriteAction { LoadTextUtil.write(project, file, this, text, -1) }
    val expected = (expectedBom ?: ByteArray(0)) + text.toByteArray(StandardCharsets.UTF_8)
    Assert.assertArrayEquals(expected, Files.readAllBytes(file.toNioPath()))
  }

  private fun withProjectBom(option: BOMForNewUTF8Files, action: () -> Unit) {
    val manager = EncodingProjectManager.getInstance(project) as EncodingProjectManagerImpl
    val oldOption = manager.bomForNewUTF8Files
    val oldCharsetName = manager.defaultCharsetName
    manager.defaultCharsetName = CharsetToolkit.UTF8
    manager.setBOMForNewUtf8Files(option)
    try {
      action()
    }
    finally {
      manager.setBOMForNewUtf8Files(oldOption)
      manager.defaultCharsetName = oldCharsetName
    }
  }

  override fun getRelativePath(): String {
    return "plugins/editorconfig/testData/org/editorconfig/configmanagement/encoding"
  }
}
