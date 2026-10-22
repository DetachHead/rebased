// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.navigation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsContexts.Separator;
import com.intellij.platform.backend.navigation.NavigationRequest;
import com.intellij.platform.ide.navigation.NavigateUtil;
import com.intellij.platform.ide.navigation.NavigationOptions;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Dmitry Avdeev
 * @author Konstantin Bulenkov
 */
public class GotoRelatedItem implements Navigatable {
  private final @Separator String myGroup;
  private final int myMnemonic;
  private final @Nullable SmartPsiElementPointer<PsiElement> myElementPointer;
  private final @Nullable Project myProject;
  public static final String DEFAULT_GROUP_NAME = "";

  protected GotoRelatedItem(@Nullable PsiElement element, @Separator String group, final int mnemonic) {
    myProject = element == null ? null : element.getProject();
    myElementPointer = element == null ? null : SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    myGroup = group;
    myMnemonic = mnemonic;
  }

  /**
   * Creates an item with a project for navigation that does not have a PSI target.
   */
  @ApiStatus.Experimental
  protected GotoRelatedItem(@NotNull Project project, @Nullable PsiElement element, @Separator String group, int mnemonic) {
    myProject = project;
    myElementPointer = element == null ? null : SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    myGroup = group;
    myMnemonic = mnemonic;
  }
  
  public GotoRelatedItem(@NotNull PsiElement element, @Separator String group) {
    this(element, group, -1);
  }

  public GotoRelatedItem(@NotNull PsiElement element) {
    this(element, DEFAULT_GROUP_NAME);
  }

  /**
   * Submits the navigation to the target of this item and returns before it completes.
   */
  public void navigate() {
    navigate(true);
  }

  @Override
  public void navigate(boolean requestFocus) {
    if (myProject != null && !myProject.isDisposed()) {
      onChosen();
      NavigateUtil.requestNavigate(myProject, this, NavigationOptions.defaultOptions().requestFocus(requestFocus));
    }
  }

  @Override
  public boolean canNavigate() {
    return myProject != null && !myProject.isDisposed();
  }

  @ApiStatus.Experimental
  public final @Nullable Project getProject() {
    return myProject;
  }

  /**
   * Runs once when the user selects this item, before the platform submits navigation.
   * Use this callback for selection analytics. Do not start navigation here.
   */
  @ApiStatus.Experimental
  @RequiresEdt
  public void onChosen() {
  }

  /**
   * Computes the target request without starting navigation.
   * Override this method for navigation that does not use the PSI target.
   * A null request ends navigation. The platform does not call {@link #navigate()} as a fallback.
   */
  @ApiStatus.Experimental
  @RequiresBackgroundThread
  @RequiresReadLock
  @Override
  public @Nullable NavigationRequest navigationRequest() {
    var element = getElement();
    return element instanceof Navigatable navigatable ? navigatable.navigationRequest() : null;
  }

  public @Nullable @NlsContexts.ListItem String getCustomName() {
    return null;
  }

  public @Nullable @Nls String getCustomContainerName() {
    return null;
  }

  public @Nullable Icon getCustomIcon() {
    return null;
  }

  public @Nullable PsiElement getElement() {
    return myElementPointer == null ? null : myElementPointer.getElement();
  }

  public int getMnemonic() {
    return myMnemonic;
  }
  public static List<GotoRelatedItem> createItems(@NotNull Collection<? extends PsiElement> elements) {
    return createItems(elements, DEFAULT_GROUP_NAME);
  }

  public static List<GotoRelatedItem> createItems(@NotNull Collection<? extends PsiElement> elements, @Separator String group) {
    List<GotoRelatedItem> items = new ArrayList<>(elements.size());
    for (PsiElement element : elements) {
      items.add(new GotoRelatedItem(element, group));
    }
    return items;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    GotoRelatedItem item = (GotoRelatedItem)o;

    if (myElementPointer != null ? !myElementPointer.equals(item.myElementPointer) : item.myElementPointer != null) return false;

    return true;
  }

  public @Separator String getGroup() {
    return myGroup;
  }

  @Override
  public int hashCode() {
    return myElementPointer != null ? myElementPointer.hashCode() : 0;
  }
}
