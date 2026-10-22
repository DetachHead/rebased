package org.intellij.plugins.markdown.lang.references

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object ReferenceUtil {
  @JvmStatic
  fun findFileReference(references: MutableList<in PsiReference>): FileReference? {
    val reference = references.asSequence().filterIsInstance<FileReference>().firstOrNull()
    return reference?.fileReferenceSet?.lastReference
  }

  @ApiStatus.Internal
  fun String.isRelativePathLike(): Boolean {
    if (startsWith('/')) return false
    if (any(Char::isWhitespace)) return false
    if (contains("://")) return false
    if (contains('/')) return true
    return FileTypeRegistry.getInstance().getFileTypeByFileName(this) != UnknownFileType.INSTANCE
  }

  val linkDestinationPattern: PsiElementPattern.Capture<MarkdownLinkDestination> =
    PlatformPatterns.psiElement(MarkdownLinkDestination::class.java)
}
