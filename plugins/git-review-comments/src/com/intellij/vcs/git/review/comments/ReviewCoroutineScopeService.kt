// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * Project-level [CoroutineScope] for [ReviewChangesAction] to launch [openReviewSession] from,
 * since [com.intellij.openapi.actionSystem.AnAction.actionPerformed] is not itself a suspend
 * function. Platform-registered via constructor injection (see `Service` kdoc).
 */
@Service(Service.Level.PROJECT)
internal class ReviewCoroutineScopeService(val cs: CoroutineScope)
