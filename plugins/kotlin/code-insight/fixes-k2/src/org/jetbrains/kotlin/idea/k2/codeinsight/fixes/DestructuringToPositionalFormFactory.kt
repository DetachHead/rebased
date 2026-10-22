// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.types.isSubtypeOf
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.codeinsight.utils.getDestructuredClassType
import org.jetbrains.kotlin.idea.codeinsight.utils.isPositionalDestructuringType
import org.jetbrains.kotlin.idea.codeinsights.impl.base.quickFix.ConvertToPositionalDestructuringFix
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.resolve.calls.util.isSingleUnderscore

internal object DestructuringToPositionalFormFactory {
    val convertToPositionalFormOnShortFormNameMismatch =
        KotlinQuickFixFactory.ModCommandBased<KaFirDiagnostic.DestructuringShortFormNameMismatch> { createFix(it.psi) }

    val convertToPositionalFormOnShortFormUnderscore =
        KotlinQuickFixFactory.ModCommandBased<KaFirDiagnostic.DestructuringShortFormUnderscore> { createFix(it.psi) }

    val convertToPositionalFormOnShortUnderscoreWithoutRename =
        KotlinQuickFixFactory.ModCommandBased<KaFirDiagnostic.NameBasedDestructuringUnderscoreWithoutRenaming> { createFix(it.psi) }

    val convertToPositionalFormOnShortFormNonDataClass =
        KotlinQuickFixFactory.ModCommandBased<KaFirDiagnostic.DestructuringShortFormOfNonDataClass> { createFix(it.psi) }

    val convertToPositionalFormOnUnresolvedReference =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.UnresolvedReference ->
            val psi = diagnostic.psi
            if (!psi.languageVersionSettings.supportsFeature(LanguageFeature.EnableNameBasedDestructuringShortForm)) {
                return@ModCommandBased emptyList()
            }

            val entry = psi.getNonStrictParentOfType<KtDestructuringDeclarationEntry>()
                ?: return@ModCommandBased emptyList()

            val declaration = entry.parent as? KtDestructuringDeclaration ?: return@ModCommandBased emptyList()

            if (psi.textRange != entry.nameIdentifier?.textRange) return@ModCommandBased emptyList()
            if (declaration.isFullForm || declaration.hasSquareBrackets()) return@ModCommandBased emptyList()

            val type = declaration.getDestructuredClassType() ?: return@ModCommandBased emptyList()
            if (!isPositionalDestructuringType(type) && !type.isSubtypeOf(StandardClassIds.MapEntry)) return@ModCommandBased emptyList()

            createFix(declaration, supportsFixAll = false)
        }

    private fun createFix(psi: PsiElement): List<ConvertToPositionalDestructuringFix> {
        val entry = psi as? KtDestructuringDeclarationEntry ?: return emptyList()
        val declaration = entry.parent as? KtDestructuringDeclaration ?: return emptyList()

        return createFix(declaration)
    }

    private fun createFix(declaration: KtDestructuringDeclaration, supportsFixAll: Boolean = true): List<ConvertToPositionalDestructuringFix> {
        if (declaration.entries.all { it.isSingleUnderscore }) return emptyList()

        return listOf(ConvertToPositionalDestructuringFix(declaration, supportsFixAll))
    }
}
