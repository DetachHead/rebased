// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.util.annotations.paths;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

/**
 * Marks a path string in the native format of the local IDE host machine.
 * <p>
 * Unlike {@link OsPath}, a {@code @LocalPath} string always belongs to the local machine.
 * You always know the environment without additional context.
 * <p>
 * You can pass a {@code @LocalPath} string to {@link java.nio.file.Path#of}
 * to work with local files.
 * Do not use it as a path within a remote environment.
 *
 * @see OsPath
 * @see NioPath
 */
@ApiStatus.Internal
@Retention(RetentionPolicy.SOURCE)
@Target({FIELD, LOCAL_VARIABLE, PARAMETER, METHOD, TYPE_USE})
public @interface LocalPath {
}
