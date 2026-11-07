// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight.inspections

import com.intellij.codeInspection.CleanupLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.expressions.contextSensitiveResolutionStatus
import org.jetbrains.kotlin.analysis.api.resolution.KaContextSensitiveResolutionStatus
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.asUnit
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinApplicableInspectionBase
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.ApplicabilityRange
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Marks explicit qualifiers that can be dropped in favor of
 * [context-sensitive resolution](https://github.com/Kotlin/KEEP/issues/379) (CSR) and offers a quick fix to remove them.
 *
 * For example, with CSR enabled:
 * ```
 * enum class Foo { BAR }
 *
 * fun usage(foo: Foo) {
 *     foo == Foo.BAR // the 'Foo.' qualifier is redundant and can be removed
 * }
 * ```
 *
 * This inspection reports *only* CSR-removable qualifiers. [RemoveRedundantQualifierNameInspection] intentionally skips them.
 *
 * The inspection is disabled by default, so the user must enable it explicitly (see KTIJ-34340).
 * Highlighting all CSR qualifiers by default is currently considered to be too noisy.
 *
 * The inspection is available only when [LanguageFeature.ContextSensitiveResolutionUsingExpectedType] is enabled.
 * Without this feature, the shortened reference does not resolve.
 *
 * The check uses [contextSensitiveResolutionStatus] from the Analysis API (see KT-85206).
 */
internal class RedundantContextSensitiveResolutionQualifierInspection :
    KotlinApplicableInspectionBase.Simple<KtElement, Unit>(),
    CleanupLocalInspectionTool {

    override fun isAvailableForFile(file: PsiFile): Boolean {
        // Removing a CSR qualifier is only safe when the feature is enabled; otherwise the code would stop compiling.
        return file.languageVersionSettings.supportsFeature(LanguageFeature.ContextSensitiveResolutionUsingExpectedType)
    }

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): KtVisitorVoid = object : KtVisitorVoid() {
        override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
            visitTargetElement(expression, holder, isOnTheFly)
        }

        override fun visitUserType(type: KtUserType) {
            visitTargetElement(type, holder, isOnTheFly)
        }
    }

    override fun isApplicableByPsi(element: KtElement): Boolean =
        element.detectNameExpressionWithQualifier() != null

    override fun getApplicableRanges(element: KtElement): List<TextRange> {
        return ApplicabilityRange.single(element) {
            it.detectNameExpressionWithQualifier()?.qualifier
        }
    }

    context(session: KaSession)
    override fun prepareContext(element: KtElement): Unit? {
        val referenceExpression = element.detectNameExpressionWithQualifier()?.referenceExpression ?: return null

        val status = referenceExpression.contextSensitiveResolutionStatus
        val qualifierCanBeRemoved = status is KaContextSensitiveResolutionStatus.QualifierCanBeRemoved

        return qualifierCanBeRemoved.asUnit
    }

    override fun getProblemDescription(element: KtElement, context: Unit): String =
        KotlinBundle.message("inspection.redundant.context.sensitive.resolution.qualifier.problem.description")

    override fun createQuickFix(
        element: KtElement,
        context: Unit,
    ): KotlinModCommandQuickFix<KtElement> = RemoveQualifierQuickFix
}

/**
 * @property referenceExpression the short name. Its [contextSensitiveResolutionStatus] tells if the [qualifier] is removable.
 * @property qualifier the part before the short name. [RemoveQualifierQuickFix] removes it.
 */
private data class NameExpressionWithQualifier(
    val referenceExpression: KtSimpleNameExpression,
    val qualifier: KtElement,
)

/**
 * Splits a qualified name into its [NameExpressionWithQualifier] parts.
 * The function checks only the PSI. It does not check if the qualifier is removable.
 *
 * Returns `null` if the element has no qualifier, or if its selector is not a simple name.
 */
private fun KtElement.detectNameExpressionWithQualifier(): NameExpressionWithQualifier? = when (this) {
    is KtDotQualifiedExpression -> {
        val referenceExpression = selectorExpression as? KtNameReferenceExpression ?: return null
        val qualifier = receiverExpression

        NameExpressionWithQualifier(referenceExpression, qualifier)
    }

    is KtUserType -> {
        val referenceExpression = referenceExpression ?: return null
        val qualifier = qualifier ?: return null

        NameExpressionWithQualifier(referenceExpression, qualifier)
    }

    else -> null
}
