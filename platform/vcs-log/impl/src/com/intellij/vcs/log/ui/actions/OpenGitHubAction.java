// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.actions;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsLogBundle;
import com.intellij.vcs.log.VcsLogDataKeys;
import com.intellij.vcs.log.VcsLogFilterCollection;
import com.intellij.vcs.log.VcsLogUi;
import com.intellij.vcs.log.impl.VcsLogManager;
import com.intellij.vcs.log.impl.VcsLogTabLocation;
import com.intellij.vcs.log.impl.VcsProjectLog;
import com.intellij.vcs.log.statistics.VcsLogUsageTriggerCollector;
import com.intellij.vcs.log.ui.MainVcsLogUi;
import com.intellij.vcs.log.ui.VcsLogInternalDataKeys;
import com.intellij.vcs.log.util.VcsLogUtil;
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class OpenGitHubAction extends DumbAwareAction {

  protected OpenGitHubAction() {
    getTemplatePresentation().setText(() -> getText(VcsLogBundle.message("github.open")));
    getTemplatePresentation().setDescription(() -> getDescription(VcsLogBundle.message("github.description")));
  }


  protected @NotNull @Nls(capitalization = Nls.Capitalization.Sentence) String getDescription(@Nls @NotNull String vcsName) {
    return VcsLogBundle.message("vcs.log.action.description.open.new.tab.with.log", vcsName);
  }

  protected @NotNull @Nls(capitalization = Nls.Capitalization.Title) String getText(@Nls @NotNull String vcsName) {
    return VcsLogBundle.message("vcs.log.action.open.new.tab.with.log", vcsName);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    BrowserUtil.open("https://github.com/obiscr/intellij-community/tree/git-client");
  }

  protected @NotNull VcsLogFilterCollection getFilters(@NotNull Project project, @NotNull AnActionEvent e) {
    VcsLogUi logUi = e.getData(VcsLogDataKeys.VCS_LOG_UI);
    if (logUi == null) return VcsLogFilterObject.collection();
    return logUi.getFilterUi().getFilters();
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
