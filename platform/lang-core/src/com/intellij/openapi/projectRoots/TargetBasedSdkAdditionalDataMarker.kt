// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.projectRoots

import org.jetbrains.annotations.ApiStatus

/**
 * A marker interface used instead of `com.intellij.execution.target.TargetBasedSdkAdditionalData` in modules that don't have access to the
 * latter.
 */
@ApiStatus.Internal
interface TargetBasedSdkAdditionalDataMarker : SdkAdditionalData