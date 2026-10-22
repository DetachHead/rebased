// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.projectRoots;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.SimpleJavaParameters;
import com.intellij.execution.target.TargetEnvironmentRequest;
import com.intellij.execution.target.TargetProgressIndicator;
import com.intellij.execution.target.TargetedCommandLineBuilder;
import com.intellij.execution.target.local.LocalTargetEnvironment;
import com.intellij.execution.target.local.LocalTargetEnvironmentRequest;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

public final class JavaCommandLineCompositionUtil {
  private JavaCommandLineCompositionUtil() {
  }

  @ApiStatus.Internal
  public static @NotNull TargetedCommandLineBuilder setupJVMCommandLine(
    @NotNull SimpleJavaParameters javaParameters,
    @NotNull TargetEnvironmentRequest request
  ) throws CantRunException {
    var setup = new JdkCommandLineSetup(request);
    setup.setupJavaExePath(javaParameters);
    setup.setupCommandLine(javaParameters);
    return setup.getCommandLine();
  }

  public static @NotNull GeneralCommandLine setupJVMCommandLine(@NotNull SimpleJavaParameters javaParameters) throws CantRunException {
    var request = new LocalTargetEnvironmentRequest();
    var builder = setupJVMCommandLine(javaParameters, request);
    LocalTargetEnvironment environment;
    try {
      environment = request.prepareEnvironment(TargetProgressIndicator.EMPTY);
    }
    catch (ExecutionException e) {
      throw new CantRunException(e.getMessage(), e);
    }
    return environment.createGeneralCommandLine(builder.build());
  }}
