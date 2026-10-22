// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.editorconfig.configmanagement

import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.encoding.Utf8BomOptionProvider
import com.intellij.util.ThreeState
import org.editorconfig.Utils
import java.nio.charset.StandardCharsets

internal class EditorConfigUtf8BomOptionProvider : Utf8BomOptionProvider {
  override fun getBOMDecisionForNewUtf8File(file: VirtualFile): ThreeState {
    if (!Utils.isApplicableTo(file)) return ThreeState.UNSURE
    val project = ProjectLocator.getInstance().guessProjectForFile(file)
    val charsetData = EditorConfigEncodingCache.getInstance().getCharsetData(project = project, virtualFile = file, withCache = true)
    if (charsetData == null || charsetData.isIgnored || charsetData.getCharset() != StandardCharsets.UTF_8) {
      return ThreeState.UNSURE
    }
    return ThreeState.fromBoolean(charsetData.isUseBom)
  }
}
