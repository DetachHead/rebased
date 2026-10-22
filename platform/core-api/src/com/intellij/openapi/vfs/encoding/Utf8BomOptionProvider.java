// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.encoding;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Allows to overwrite project level UTF-8 BOM option for a specific virtual file.
 * <p>
 * The IDE asks the providers in the extension point order and uses the first explicit decision.
 * If all providers return {@link ThreeState#UNSURE}, the IDE uses the project or application setting.
 */
@ApiStatus.OverrideOnly
public interface Utf8BomOptionProvider {
  ExtensionPointName<Utf8BomOptionProvider> EP_NAME = new ExtensionPointName<>("com.intellij.utf8BomOptionProvider");

  /**
   * @param file The file to check.
   * @return {@code true} if BOM should be added for UTF-8-encoded file.
   * {@code false} does not prohibit a BOM.
   * @see EncodingManager#shouldAddBOMForNewUtf8File()
   * @deprecated Override {@link #getBOMDecisionForNewUtf8File} instead.
   */
  @Deprecated
  default boolean shouldAddBOMForNewUtf8File(@NotNull VirtualFile file) {
    return false;
  }

  /**
   * @param file The new UTF-8-encoded file to check.
   * @return {@link ThreeState#YES} to add a BOM, {@link ThreeState#NO} to prohibit a BOM,
   * or {@link ThreeState#UNSURE} to defer to other providers and the project or application setting.
   * @see EncodingManager#shouldAddBOMForNewUtf8File()
   */
  default @NotNull ThreeState getBOMDecisionForNewUtf8File(@NotNull VirtualFile file) {
    return shouldAddBOMForNewUtf8File(file) ? ThreeState.YES : ThreeState.UNSURE;
  }
}
