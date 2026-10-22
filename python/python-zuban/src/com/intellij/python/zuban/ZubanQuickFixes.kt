// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.zuban

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.messageIfStringOrEmpty
import com.intellij.psi.PsiFile
import com.intellij.python.lsp.core.PyLspUnresolvedReferenceCodes
import com.intellij.python.lsp.core.pyLspImportedReference
import com.intellij.python.lsp.core.pyLspUnresolvedReferenceQuickFixes
import com.intellij.python.lsp.core.pyLspWrapQuickFixes
import com.jetbrains.python.inspections.quickfix.InstallPackageQuickFix
import org.eclipse.lsp4j.Diagnostic
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting

/** The code of a module that has no stubs, while a stub package for it exists on PyPI. */
private const val IMPORT_UNTYPED = "import-untyped"

/** The Mypy error codes that zuban uses for a reference it cannot resolve. */
private val ZUBAN_UNRESOLVED_REFERENCE_CODES = PyLspUnresolvedReferenceCodes(
  unknownName = setOf("name-defined"),
  missingImport = setOf("import-not-found"),
  missingModuleAttribute = setOf("attr-defined"),
)

/** The stub package in a hint such as `Hint: "python3 -m pip install types-requests"`. */
private val STUB_PACKAGE_HINT = Regex("""pip install ([A-Za-z0-9][A-Za-z0-9._-]*)""")

/**
 * The PyCharm quick fixes for a zuban [diagnostic]. The fixes of the server itself, which add a
 * `# type: ignore[code]` comment, come on top of these.
 */
@ApiStatus.Internal
@VisibleForTesting
fun zubanQuickFixes(file: PsiFile, diagnostic: Diagnostic, textRange: TextRange): List<IntentionAction> {
  if (diagnostic.code?.left == IMPORT_UNTYPED) return installStubPackageQuickFixes(file, diagnostic, textRange)
  return pyLspUnresolvedReferenceQuickFixes(file, diagnostic, textRange, ZUBAN_UNRESOLVED_REFERENCE_CODES)
}

private fun installStubPackageQuickFixes(file: PsiFile, diagnostic: Diagnostic, textRange: TextRange): List<IntentionAction> {
  val message = diagnostic.messageIfStringOrEmpty
  val stubPackage = zubanStubPackage(message) ?: return emptyList()
  val reference = pyLspImportedReference(file, textRange) ?: return emptyList()
  return pyLspWrapQuickFixes(reference, message, listOf(InstallPackageQuickFix(stubPackage)))
}

/** The stub package that the `import-untyped` [message] of zuban asks to install, or `null` when it names none. */
@ApiStatus.Internal
@VisibleForTesting
fun zubanStubPackage(message: String): String? = STUB_PACKAGE_HINT.find(message)?.groupValues?.get(1)
