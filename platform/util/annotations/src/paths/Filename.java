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
 * Marks a string that contains a bare filename with no path components.
 * The string must not contain a directory separator, a drive letter, or a path prefix.
 * For example, {@code "file.txt"} is valid but {@code "dir/file.txt"} is not.
 * <p>
 * A filename string carries no environment-specific formatting and is compatible
 * with {@link java.nio.file.Path#resolve(String)} and with the additional arguments
 * of {@link java.nio.file.Path#of(String, String...)}.
 */
@ApiStatus.Internal
@Retention(RetentionPolicy.SOURCE)
@Target({FIELD, LOCAL_VARIABLE, PARAMETER, METHOD, TYPE_USE})
public @interface Filename {
}
