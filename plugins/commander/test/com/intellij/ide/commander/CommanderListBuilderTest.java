// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.commander;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.projectView.impl.AbstractProjectTreeStructure;
import com.intellij.ide.projectView.impl.ClassesTreeStructureProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.PathManagerEx;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.projectView.TestProjectTreeStructure;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ProjectViewTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.uiDesigner.projectView.FormMergerTreeStructureProvider;
import com.intellij.util.IncorrectOperationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import javax.swing.ListModel;
import java.io.IOException;
import java.nio.file.Path;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.disposableFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.moduleFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.tempPathFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@TestApplication
public class CommanderListBuilderTest {
  @SuppressWarnings("deprecation")
  private final TestFixture<Project> projectFixture = projectFixture(tempPathFixture(), OpenProjectTask.build(), true);
  private final TestFixture<Module> moduleFixture =
    moduleFixture(projectFixture, "commander", JavaModuleType.JAVA_MODULE_ENTITY_TYPE_ID_NAME);
  private final TestFixture<Path> contentPathFixture = tempPathFixture();
  private final TestFixture<Disposable> disposableFixture = disposableFixture();

  private Project myProject;
  private VirtualFile myContentRoot;
  private TestProjectTreeStructure myStructure;
  private Commander myCommander;

  @BeforeEach
  void setUp(TestInfo testInfo) throws IOException {
    myProject = projectFixture.get();
    Module module = moduleFixture.get();
    String testName = PlatformTestUtil.getTestName(testInfo.getTestMethod().orElseThrow().getName(), true);
    Path testDataDir = Path.of(PathManagerEx.getCommunityHomePath(), "java/java-tests/testData/projectView", testName);
    Path contentPath = contentPathFixture.get().resolve(testName);
    NioFiles.copyRecursively(testDataDir, contentPath);

    EdtTestUtil.runInEdtAndWait(() -> {
      myContentRoot = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(contentPath);
      PsiTestUtil.addContentRoot(module, myContentRoot);
      PsiTestUtil.addSourceRoot(module, myContentRoot.findChild("src"));
      IndexingTestUtil.waitUntilIndexesAreReady(myProject);
      ProjectViewTestUtil.setupImpl(myProject, true);

      myStructure = new TestProjectTreeStructure(myProject, disposableFixture.get());
      myCommander = new Commander(myProject) {
        @Override
        protected void updateToolWindowTitle(final CommanderPanel activePanel) {
        }

        @Override
        protected AbstractProjectTreeStructure createProjectTreeStructure() {
          return myStructure;
        }
      };
    });
  }

  @AfterEach
  void tearDown() {
    if (myCommander != null) {
      EdtTestUtil.runInEdtAndWait(() -> Disposer.dispose(myCommander));
      myCommander = null;
    }
  }

  @Test
  public void testStandardProviders() {
    EdtTestUtil.runInEdtAndWait(() -> {
      useStandardProviders();

      myCommander.enterElementInActivePanel(getContentDirectory());
      checkListInActivePanel("""
                               [ .. ]
                               PsiDirectory: src
                               """);

      myCommander.switchActivePanel();
      myCommander.enterElementInActivePanel(getPackageDirectory());
      checkListInActivePanel(
        """
          [ .. ]
          Class1
          Class2.java
          Class4.java
          Form1
          Form1.form
          Form2.form
          """);

      CommandProcessor.getInstance().executeCommand(myProject, () -> ApplicationManager.getApplication().runWriteAction(() -> {
        try {
          findClassInDirectory("Class1").setName("Class1_renamed");
        }
        catch (IncorrectOperationException e) {
          fail();
        }
      }), null, null);


      checkListInActivePanel(
        """
          [ .. ]
          Class1_renamed
          Class2.java
          Class4.java
          Form1
          Form1.form
          Form2.form
          """);
    });
  }

  private PsiClass findClassInDirectory(final String className) {
    final PsiClass[] classes = JavaDirectoryService.getInstance().getClasses(getPackageDirectory());
    for (PsiClass aClass : classes) {
      if (aClass.getName().equals(className)) {
        return aClass;
      }
    }
    fail(className + " not found");
    return null;
  }

  @Test
  public void testShowClassMembers() {
    EdtTestUtil.runInEdtAndWait(() -> {
      useStandardProviders();
      myStructure.setShowMembers(true);
      PsiField field = findClassInDirectory("Class1").getFields()[1];
      myCommander.selectElementInRightPanel(field, field.getContainingFile().getVirtualFile());

      checkListInRightPanel("""
                              [ .. ]
                              InnerClass
                              getValue(): int
                              myField1: boolean
                              myField2: boolean
                              """);
      checkSelectedElement(field, myCommander.getRightPanel());

      myCommander.selectElementInLeftPanel(getPackageDirectory(), getPackageDirectory().getVirtualFile());
      checkListInPanel(myCommander.getLeftPanel(), """
        [ .. ]
        PsiDirectory: package1
        """);
      checkSelectedElement(getPackageDirectory(), myCommander.getLeftPanel());

      myCommander.syncViews();
      myCommander.swapPanels();
    });
  }

  @Test
  public void testUpdateProjectView() {
    EdtTestUtil.runInEdtAndWait(() -> {
      myStructure.setProviders(new ClassesTreeStructureProvider(myProject), new FormMergerTreeStructureProvider(myProject));

      myStructure.setShowMembers(true);
      final PsiClass formClass = findClassInDirectory("Form1");
      myCommander.selectElementInRightPanel(formClass, formClass.getContainingFile().getVirtualFile());

      checkListInRightPanel("""
                              [ .. ]
                              Form1
                              Form1.form
                              """);

      WriteCommandAction.runWriteCommandAction(null, () -> formClass.delete());


      PlatformTestUtil.waitForAlarm(600);

      checkListInRightPanel("""
                              [ .. ]
                              Class1
                              Class2.java
                              Class4.java
                              Form1.form
                              Form2.form
                              """);
    });
  }

  private void useStandardProviders() {
    myStructure.setProviders(new ClassesTreeStructureProvider(myProject));
  }

  private PsiDirectory getContentDirectory() {
    return PsiManager.getInstance(myProject).findDirectory(myContentRoot);
  }

  private PsiDirectory getPackageDirectory() {
    return PsiManager.getInstance(myProject).findDirectory(myContentRoot.findFileByRelativePath("src/com/package1"));
  }

  private void checkListInRightPanel(String expected) {
    checkListInPanel(myCommander.getRightPanel(), expected);
  }

  private static void checkSelectedElement(Object field, CommanderPanel panel) {
    assertEquals(field, CommanderPanel.getNodeElement(panel.getSelectedNode()));
  }

  private void checkListInActivePanel(String expected) {
    checkListInPanel(myCommander.getActivePanel(), expected);
  }

  private static void checkListInPanel(CommanderPanel activePanel, String expected) {
    assertEquals(expected, PlatformTestUtil.print((ListModel<?>)activePanel.getModel()));
  }
}
