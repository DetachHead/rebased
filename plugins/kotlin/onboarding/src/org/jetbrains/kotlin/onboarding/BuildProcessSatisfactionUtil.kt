// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.onboarding

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.util.indexing.DumbModeAccessType
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.KotlinFileType

@ApiStatus.Internal
object BuildProcessSatisfactionUtil {
    suspend fun getKotlinFileCount(project: Project): Int =
        countFiles(project, KotlinFileType.INSTANCE) { it.extension != "kts" } // Ignore Kotlin script files

    suspend fun getJavaFileCount(project: Project): Int {
        val javaFileType = FileTypeRegistry.getInstance().findFileTypeByName("JAVA") ?: return 0
        return countFiles(project, javaFileType) { true }
    }

    private suspend fun countFiles(
        project: Project,
        fileType: FileType,
        filter: (VirtualFile) -> Boolean
    ): Int = readAction {
        var count = 0

        DumbModeAccessType.RELIABLE_DATA_ONLY.ignoreDumbMode {
            FileTypeIndex.processFiles(fileType, { file ->
                if (filter(file)) count++
                true
            }, ProjectScope.getContentScope(project))
        }
        count
    }
}
