// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.options.newEditor;

import org.jetbrains.annotations.ApiStatus;

/**
 * A configurable component that states its own beta badge.
 * <p>
 * The settings tree reads the {@code beta} attribute of a declaration, so a node that a
 * {@link com.intellij.openapi.options.ex.ConfigurableWrapper} holds needs nothing else. A child that a parent page
 * builds itself has no wrapper and no declaration of its own, so it answers here. The answer must cost no class load,
 * because the tree asks it while it paints a row.
 */
@ApiStatus.Internal
public interface BetaConfigurable {
  boolean isBeta();
}
