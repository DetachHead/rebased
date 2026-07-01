// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.SettingsCategory;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@State(name = "BaseRefactoringSettings", storages = @Storage("baseRefactoring.xml"), category = SettingsCategory.CODE)
public final class RefactoringSettings implements PersistentStateComponent<RefactoringSettings> {
  public static RefactoringSettings getInstance() {
    return ApplicationManager.getApplication().getService(RefactoringSettings.class);
  }

  // all rename refactoring stuff is disabled in rebased. only the rename itself is supported.

  public boolean SAFE_DELETE_WHEN_DELETE = false;
  public boolean SAFE_DELETE_SEARCH_IN_COMMENTS = false;
  public boolean SAFE_DELETE_SEARCH_IN_NON_JAVA = false;

  public boolean RENAME_SEARCH_IN_COMMENTS_FOR_FILE = false;
  public boolean RENAME_SEARCH_FOR_TEXT_FOR_FILE = false;

  public boolean RENAME_SEARCH_FOR_REFERENCES_FOR_FILE = false;
  public boolean RENAME_SEARCH_FOR_REFERENCES_FOR_DIRECTORY = false;

  public boolean MOVE_SEARCH_FOR_REFERENCES_FOR_FILE = false;

  public boolean ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE = false;

  public boolean RENAME_DECLARATION_WHEN_RENAME_FILE = false;

  public boolean RENAME_SHOW_AUTOMATIC_RENAMING_DIALOG = false;

  @Override
  public RefactoringSettings getState() {
    return this;
  }

  @Override
  public void loadState(final @NotNull RefactoringSettings state) {
    XmlSerializerUtil.copyBean(state, this);
  }
}
