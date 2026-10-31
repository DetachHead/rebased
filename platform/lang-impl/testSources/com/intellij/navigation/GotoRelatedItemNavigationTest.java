// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.navigation;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.platform.backend.navigation.NavigationRequest;
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest;
import com.intellij.platform.ide.navigation.CaretPlacement;
import com.intellij.platform.ide.navigation.NavigateUtil;
import com.intellij.platform.ide.navigation.NavigationOptions;
import com.intellij.platform.ide.navigation.NavigationService;
import com.intellij.platform.ide.navigation.RequestedEditor;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.testFramework.LightPlatformCodeInsightTestCase;
import com.intellij.testFramework.NavigationTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.util.ui.EDT;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the threading contract of {@link GotoRelatedItem#navigate()}.
 * The default implementation must resolve its target off the EDT and under a RA
 */
public class GotoRelatedItemNavigationTest extends LightPlatformCodeInsightTestCase {
  public void testDeferredRequestHonorsTokenEnd() {
    configureFromFileText("test.txt", "hello world");
    var request = deferredRequest();
    var options = NavigationOptions.defaultOptions().caretPlacement(CaretPlacement.TOKEN_END)
      .requestedEditor(new RequestedEditor.Specific(getEditor()));

    var result = NavigationService.getInstance(getProject()).requestNavigate(request, options);
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertTrue(result.isDone());
    assertTrue(result.join());
    assertEquals(getFile().getTextLength(), getEditor().getCaretModel().getOffset());
  }

  public void testDeferredRequestPreservesCaretInsideElement() {
    configureFromFileText("test.txt", "hello world");
    getEditor().getCaretModel().moveToOffset(5);
    var request = deferredRequest();

    NavigateUtil.requestNavigate(getProject(), request, NavigationOptions.defaultOptions().preserveCaret(true));
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertSame(getEditor(), FileEditorManager.getInstance(getProject()).getSelectedTextEditor());
    assertEquals(5, getEditor().getCaretModel().getOffset());
  }

  private SourceNavigationRequest deferredRequest() {
    return ReadAction.computeBlocking(() -> {
      var file = getFile().getVirtualFile();
      var element = getFile().findElementAt(0);
      assertNotNull(element);
      assertFalse(element instanceof PsiFile);
      return new SourceNavigationRequest(file, null, null, null, file.getModificationStamp(),
                                         getEditor().getDocument().getModificationStamp(),
                                         SmartPointerManager.createPointer(element), false, false);
    });
  }

  public void testTargetIsResolvedOnBackgroundThreadUnderReadAction() {
    configureFromFileText("test.txt", "hello world");
    PsiFile file = getFile();

    RecordingItem item = ReadAction.computeBlocking(() -> new RecordingItem(file));
    item.navigate();
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertEquals("the target must be resolved exactly once", 1, item.myResolveCount);
    assertFalse("the target must not be resolved on the EDT", item.myResolvedOnEdt);
    assertTrue("the target must be resolved under a read action", item.myResolvedUnderReadAction);
  }

  public void testServiceResolvesTargetOnBackgroundThreadUnderReadAction() {
    configureFromFileText("test.txt", "hello world");
    RecordingItem item = ReadAction.computeBlocking(() -> new RecordingItem(getFile()));

    var result = NavigationService.getInstance(getProject()).requestNavigate(item, NavigationOptions.defaultOptions());
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertTrue(result.isDone());
    assertTrue(result.join());
    assertEquals(1, item.myResolveCount);
    assertFalse(item.myResolvedOnEdt);
    assertTrue(item.myResolvedUnderReadAction);
  }

  public void testDispatcherDoesNotCallLegacyOverride() {
    configureFromFileText("test.txt", "hello world");
    AtomicInteger navigateCount = new AtomicInteger();
    RecordingItem item = ReadAction.computeBlocking(() -> new RecordingItem(getFile()) {
      @Override
      public void navigate() {
        navigateCount.incrementAndGet();
        super.navigate();
      }
    });

    NavigationUtil.navigateToRelatedItem(getProject(), item, NavigationOptions.defaultOptions());
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertEquals(0, navigateCount.get());
    assertEquals(1, item.myResolveCount);
    assertFalse(item.myResolvedOnEdt);
    assertTrue(item.myResolvedUnderReadAction);
  }

  public void testNullRequestDoesNotCallLegacyOverride() {
    AtomicInteger requestCount = new AtomicInteger();
    LegacyItem item = new LegacyItem() {
      @Override
      public @Nullable NavigationRequest navigationRequest() {
        assertFalse(EDT.isCurrentThreadEdt());
        assertTrue(ApplicationManager.getApplication().isReadAccessAllowed());
        requestCount.incrementAndGet();
        return null;
      }
    };

    NavigationUtil.navigateToRelatedItem(getProject(), item, NavigationOptions.defaultOptions());
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertEquals(1, requestCount.get());
    assertEquals(0, item.myNavigateCount);
  }

  public void testServiceReturnsFalseForNullRequest() {
    var requests = new AtomicInteger();
    var target = new Navigatable() {
      @Override
      public @Nullable NavigationRequest navigationRequest() {
        requests.incrementAndGet();
        return null;
      }
    };

    var result = NavigationService.getInstance(getProject()).requestNavigate(target, NavigationOptions.defaultOptions());
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertTrue(result.isDone());
    assertFalse(result.join());
    assertEquals(1, requests.get());
  }

  @SuppressWarnings("unchecked")
  public void testPopupUsesRequestContractWithoutPsiElement() {
    AtomicInteger requestCount = new AtomicInteger();
    LegacyItem item = new LegacyItem() {
      @Override
      public @NotNull String getCustomName() {
        return "target";
      }

      @Override
      public @Nullable NavigationRequest navigationRequest() {
        requestCount.incrementAndGet();
        return null;
      }
    };
    ListPopup popup = (ListPopup)NavigationUtil.getRelatedItemsPopup(
      List.of(item), null, false, getProject(), NavigationOptions.defaultOptions());
    Disposer.register(getTestRootDisposable(), popup);

    popup.getListStep().onChosen(item, true);
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertEquals(1, requestCount.get());
    assertEquals(0, item.myNavigateCount);
  }

  public void testPlainItemKeepsOptionsForDirectAndPopupSelection() {
    configureFromFileText("test.txt", "hello world");
    var item = ReadAction.computeBlocking(() -> new GotoRelatedItem(getFile()));
    var options = NavigationOptions.defaultOptions()
      .requestFocus(false)
      .preserveCaret(true)
      .openInRightSplit(true)
      .recordAsBackHistory(false)
      .requestedEditor(new RequestedEditor.Specific(getEditor()))
      .caretPlacement(CaretPlacement.TARGET_OFFSET);
    var calls = recordSubmissions(item, options);

    NavigationUtil.navigateToRelatedItem(getProject(), item, options);
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertEquals(1, calls.get());

    var popup = (ListPopup)NavigationUtil.getRelatedItemsPopup(List.of(item), null, false, getProject(), options);
    choose(popup, getFile());
    assertEquals(2, calls.get());
  }

  public void testUtilityKeepsOptionsAndCapturesEditorFromContext() {
    configureFromFileText("test.txt", "hello world");
    var item = ReadAction.computeBlocking(() -> new GotoRelatedItem(getFile()));
    var options = NavigationOptions.defaultOptions().requestFocus(false).openInRightSplit(true).recordAsBackHistory(false);
    var context = SimpleDataContext.getSimpleContext(OpenFileDescriptor.NAVIGATE_IN_EDITOR, getEditor());
    var calls = recordSubmissions(item, options.requestedEditor(new RequestedEditor.Specific(getEditor())));

    var result = NavigateUtil.requestNavigate(getProject(), item, options, context);
    NavigationTestUtil.awaitPendingNavigation(getProject());

    assertTrue(result.isDone());
    assertTrue(result.join());
    assertEquals(1, calls.get());
  }

  public void testSelectionCallbackIsOutsideRequestComputation() throws Exception {
    var selections = new AtomicInteger();
    var requests = new AtomicInteger();
    var item = new GotoRelatedItem(getProject(), null, GotoRelatedItem.DEFAULT_GROUP_NAME, -1) {
      @Override
      public void onChosen() {
        assertTrue(EDT.isCurrentThreadEdt());
        selections.incrementAndGet();
      }

      @Override
      public @Nullable NavigationRequest navigationRequest() {
        assertFalse(EDT.isCurrentThreadEdt());
        assertTrue(ApplicationManager.getApplication().isReadAccessAllowed());
        requests.incrementAndGet();
        return null;
      }
    };

    NavigationUtil.navigateToRelatedItem(getProject(), item, NavigationOptions.defaultOptions());
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertEquals(1, selections.get());
    assertEquals(1, requests.get());

    ApplicationManager.getApplication().executeOnPooledThread(() -> ReadAction.computeBlocking(() -> {
      item.navigationRequest();
      item.navigationRequest();
      return null;
    })).get(10, TimeUnit.SECONDS);
    assertEquals(3, requests.get());
    assertEquals(1, selections.get());
  }

  public void testLegacyEntryResolvesNonPsiRequestInBackground() {
    var requests = new AtomicInteger();
    var executions = new AtomicInteger();
    var focused = new AtomicBoolean();
    var item = new GotoRelatedItem(getProject(), null, GotoRelatedItem.DEFAULT_GROUP_NAME, -1) {
      @Override
      public @Nullable NavigationRequest navigationRequest() {
        assertFalse(EDT.isCurrentThreadEdt());
        assertTrue(ApplicationManager.getApplication().isReadAccessAllowed());
        requests.incrementAndGet();
        return new Navigatable() {
          @Override
          public boolean canNavigate() {
            return true;
          }

          @Override
          public void navigate(boolean requestFocus) {
            assertTrue(EDT.isCurrentThreadEdt());
            focused.set(requestFocus);
            executions.incrementAndGet();
          }
        }.navigationRequest();
      }
    };

    item.navigate();
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertEquals(1, requests.get());
    assertEquals(1, executions.get());
    assertTrue(focused.get());

    NavigationUtil.navigateToRelatedItem(getProject(), item, NavigationOptions.defaultOptions().requestFocus(false));
    NavigationTestUtil.awaitPendingNavigation(getProject());
    assertEquals(2, requests.get());
    assertEquals(2, executions.get());
    assertFalse(focused.get());
  }

  private AtomicInteger recordSubmissions(GotoRelatedItem item, NavigationOptions options) {
    var calls = new AtomicInteger();
    var service = (NavigationService)Proxy.newProxyInstance(
      NavigationService.class.getClassLoader(), new Class<?>[]{NavigationService.class}, (proxy, method, args) -> {
        if (method.getDeclaringClass() == Object.class) {
          return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> "RecordingNavigationService";
          };
        }
        assertEquals("navigate", method.getName());
        assertSame(item, args[0]);
        assertEquals(options, args[1]);
        calls.incrementAndGet();
        return true;
      });
    ServiceContainerUtil.replaceService(getProject(), NavigationService.class, service, getTestRootDisposable());
    return calls;
  }

  @SuppressWarnings("unchecked")
  private void choose(ListPopup popup, Object value) {
    Disposer.register(getTestRootDisposable(), popup);
    popup.getListStep().onChosen(value, true);
    NavigationTestUtil.awaitPendingNavigation(getProject());
  }

  private static class LegacyItem extends GotoRelatedItem {
    int myNavigateCount;

    LegacyItem() {
      super(null, DEFAULT_GROUP_NAME, -1);
    }

    @Override
    public void navigate() {
      myNavigateCount++;
    }
  }

  private static class RecordingItem extends GotoRelatedItem {
    int myResolveCount;
    boolean myResolvedOnEdt;
    boolean myResolvedUnderReadAction;

    RecordingItem(@NotNull PsiElement element) {
      super(element);
    }

    @Override
    public @Nullable PsiElement getElement() {
      myResolveCount++;
      myResolvedOnEdt |= EDT.isCurrentThreadEdt();
      myResolvedUnderReadAction = ApplicationManager.getApplication().isReadAccessAllowed();
      return super.getElement();
    }
  }
}
