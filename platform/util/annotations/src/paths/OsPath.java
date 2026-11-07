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
 * Marks a path string in the native format of the environment the path belongs to.
 * The string carries no information about which environment that is.
 * Because of this, the JVM cannot address the path without additional context.
 * <p>
 * Contrast with {@link NioPath}: that string either identifies the local environment
 * implicitly or carries an explicit environment prefix.
 * Both forms of {@link NioPath} allow direct use with {@link java.nio.file.Path#of}.
 * <p>
 * In the Eel API, convert the string to an {@code EelPath} by calling
 * {@code EelPath.parse(string, descriptor)}, where {@code descriptor} is the
 * {@code EelDescriptor} of the environment the path belongs to.
 *
 * @see NioPath
 */
@ApiStatus.Internal
@Retention(RetentionPolicy.SOURCE)
@Target({FIELD, LOCAL_VARIABLE, PARAMETER, METHOD, TYPE_USE})
public @interface OsPath {
}
