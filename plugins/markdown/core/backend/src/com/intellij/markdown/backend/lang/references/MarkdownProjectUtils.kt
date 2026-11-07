// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.markdown.backend.lang.references

import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.intellij.plugins.markdown.lang.MarkdownFileType
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object MarkdownProjectUtils {
  @JvmStatic
  fun hasMarkdownFiles(project: Project): Boolean {
    return CachedValuesManager.getManager(project).getCachedValue(project) {
      CachedValueProvider.Result.create(
        FileTypeIndex.containsFileOfType(MarkdownFileType.INSTANCE, GlobalSearchScope.projectScope(project)),
        PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(MarkdownLanguage.INSTANCE) }
      )
    }
  }
}
