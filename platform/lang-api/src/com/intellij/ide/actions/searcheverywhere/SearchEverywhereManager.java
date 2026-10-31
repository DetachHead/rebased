// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actions.searcheverywhere;

import com.intellij.ide.actions.SearchEverywhereManagerFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Mikhail.Sokolov
 */
public interface SearchEverywhereManager {
  @ApiStatus.Internal
  String ALL_CONTRIBUTORS_GROUP_ID = "SearchEverywhereContributor.All";

  static SearchEverywhereManager getInstance(Project project) {
    // Avoid initializing SearchEverywhereManager for a project mock.
    final @Nullable Project filteredProject = (project != null && project.getProjectFilePath() == null) ? null : project;

    SearchEverywhereManager manager =
      SearchEverywhereManagerFactory.EP_NAME.computeSafeIfAny(factory ->
                                                                factory.isAvailable() ? factory.getManager(filteredProject)
                                                                                      : null);

    if (manager == null) {
      throw new IllegalStateException("SearchEverywhereManager is not available for project: " + project);
    }

    return manager;
  }

  boolean isShown();

  @Nullable
  @ApiStatus.Internal
  SearchEverywherePopupInstance getCurrentlyShownPopupInstance();

  void show(@NotNull String contributorID, @Nullable String searchText, @NotNull AnActionEvent initEvent);

  @NotNull
  String getSelectedTabID();

  void setSelectedTabID(@NotNull String tabID);

  void toggleEverywhereFilter();

  /**
   * @deprecated The old Search Everywhere is being sunset.
   *             Use {@link com.intellij.platform.searchEverywhere.providers.SeEverywhereFilter#isEverywhere()} instead.
   */
  @Deprecated
  boolean isEverywhere();

  @ApiStatus.Internal
  boolean isSplit();

  @ApiStatus.Internal
  boolean isPreviewEnabled();
}
