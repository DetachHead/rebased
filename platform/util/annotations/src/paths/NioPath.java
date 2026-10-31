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
 * Marks a path string that the multi-routing NIO file system can address directly.
 * Pass the string to {@link java.nio.file.Path#of} or {@link java.nio.file.Paths#get};
 * the file system routes it to the correct environment automatically.
 * <p>
 * For the local environment the string is a plain OS-format path.
 * For a remote environment it carries an explicit environment prefix
 * that identifies the target.
 * <p>
 * Contrast with {@link OsPath}: that string carries no environment identity,
 * so the JVM cannot address it without additional context.
 * <p>
 * The multi-routing file system is backed by the Eel API.
 * For each remote environment, an IJent process handles the file I/O.
 * To convert a {@code @NioPath} string to the native {@code @OsPath} string,
 * call {@code Path.of(nioPath).asEelPath().toString()}.
 * The method {@code asEelPath()} is defined in {@code EelPathConversions}.
 *
 * @see OsPath
 */
@ApiStatus.Internal
@Retention(RetentionPolicy.SOURCE)
@Target({FIELD, LOCAL_VARIABLE, PARAMETER, METHOD, TYPE_USE})
public @interface NioPath {
}
