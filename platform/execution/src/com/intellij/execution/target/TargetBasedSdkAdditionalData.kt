// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.target

import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.TargetBasedSdkAdditionalDataMarker

interface TargetBasedSdkAdditionalData : SdkAdditionalData, TargetBasedSdkAdditionalDataMarker {
  val targetEnvironmentConfiguration: TargetEnvironmentConfiguration?
}