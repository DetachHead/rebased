// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.progress

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.Contract

/**
 * Defines the frames that show a background progress.
 */
@ApiStatus.Experimental
sealed interface BackgroundTaskOwner {

  companion object {
    /**
     * The progress will be shown in the frame of [project]
     */
    @Contract(pure = true)
    @JvmStatic
    fun project(project: Project): BackgroundTaskOwner = ProjectBackgroundTaskOwner(project)

    /**
     * The progress will be replicated across all IDE frames.
     * This is useful for application-wide activities, such as VFS refresh
     */
    @Contract(pure = true)
    @JvmStatic
    fun allFrames(): BackgroundTaskOwner {
      return AllFramesBackgroundTaskOwner
    }

    /**
     * The UI position of the progress bar is implementation-defined.
     * For example, in monolith IDE the progress bar is visible in the last focused frame.
     */
    @Contract(pure = true)
    @JvmStatic
    fun guess(): BackgroundTaskOwner = GuessBackgroundTaskOwner
  }

}

@Internal
class ProjectBackgroundTaskOwner internal constructor(val project: Project) : BackgroundTaskOwner {
  override fun toString(): String = "ProjectBackgroundTaskOwner($project)"
}

@Internal
object GuessBackgroundTaskOwner : BackgroundTaskOwner {
  override fun toString(): String = "GuessBackgroundTaskOwner"
}

@Internal
object AllFramesBackgroundTaskOwner : BackgroundTaskOwner {
  override fun toString(): String = "AllFramesBackgroundTaskOwner"
}

/**
 * Rhizome adapter
 */
@Internal
@Serializable
enum class BackgroundTaskOwnerKind {
  /** The frame of [TaskInfoEntity.projectId] shows the task. */
  PROJECT,

  // the semantics is intentionally unclear -- it is defined at least in monolith.
  GUESS,

  ALL_FRAMES,
}

@get:Internal
val BackgroundTaskOwner.kind: BackgroundTaskOwnerKind
  get() = when (this) {
    is ProjectBackgroundTaskOwner -> BackgroundTaskOwnerKind.PROJECT
    is GuessBackgroundTaskOwner -> BackgroundTaskOwnerKind.GUESS
    is AllFramesBackgroundTaskOwner -> BackgroundTaskOwnerKind.ALL_FRAMES
  }
