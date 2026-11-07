// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.tempPathFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the deterministic, import-free parts of {@link MavenModelProblemFilter}: parsing of the
 * {@code @ [<modelId>, <source>, ]line N[, column N]} location suffix, the highlighted span, the resolved
 * line/column, and that non-location lines produce no hyperlink.
 * <p>
 * Resolution that requires an imported reactor (an "own pom" problem via the model header, a cross-model
 * problem via the model id) is covered by {@code MavenModelProblemNavigationTest}.
 */
@TestApplication
public class MavenModelProblemFilterTest {
  private static final TestFixture<Path> tempDir = tempPathFixture();
  private static final TestFixture<Project> project = projectFixture(tempDir);

  private MavenModelProblemFilter myFilter;
  private VirtualFile myPom;
  private String myPomPath;

  @BeforeEach
  public void setUp() throws IOException {
    myFilter = new MavenModelProblemFilter(project.get());
    Path pomFile = tempDir.get().resolve("pom.xml");
    Files.writeString(pomFile,
                      """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>org.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-jar-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """);
    myPom = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(pomFile);
    assertNotNull(myPom, "File not found in VFS: " + pomFile);
    myPomPath = myPom.getPath();
  }

  @Test
  public void testNavigatesToExplicitSourcePathWithLineAndColumn() {
    String line = "[WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-jar-plugin is missing. "
                  + "@ org.example:app:pom:1.0, " + myPomPath + ", line 8, column 13";

    Filter.Result result = myFilter.applyFilter(line, line.length());
    assertNotNull(result, "Expected a hyperlink for a model problem with an explicit source path");

    Filter.ResultItem item = single(result);
    OpenFileHyperlinkInfo info = assertInstanceOf(OpenFileHyperlinkInfo.class, item.getHyperlinkInfo());
    assertEquals(myPom, info.getVirtualFile());

    // the whole "@ ... line 8, column 13" tail is highlighted
    assertEquals(line.indexOf("@ "), item.getHighlightStartOffset());
    assertEquals(line.length(), item.getHighlightEndOffset());

    // line/column are reported 0-based to the descriptor
    assertLineColumn(info, 7, 12);
  }

  @Test
  public void testNavigatesToExplicitSourcePathWithoutColumn() {
    String line = "[WARNING] expression '${pom.version}' is deprecated. @ org.example:app:pom:1.0, " + myPomPath + ", line 3";

    Filter.Result result = myFilter.applyFilter(line, line.length());
    assertNotNull(result);

    Filter.ResultItem item = single(result);
    OpenFileHyperlinkInfo info = (OpenFileHyperlinkInfo)item.getHyperlinkInfo();
    assertEquals(myPom, info.getVirtualFile());
    assertEquals(line.indexOf("@ "), item.getHighlightStartOffset());
    assertEquals(line.length(), item.getHighlightEndOffset());
    assertLineColumn(info, 2, 0);
  }

  @Test
  public void testModelHeaderLineIsNotAHyperlink() {
    String line = "[WARNING] Some problems were encountered while building the effective model for org.example:app:jar:1.0";
    assertNull(myFilter.applyFilter(line, line.length()));
  }

  @Test
  public void testUnrelatedLineIsNotAHyperlink() {
    assertNull(myFilter.applyFilter("[INFO] BUILD SUCCESS", 20));
  }

  @Test
  public void testUnknownModelWithoutSourceIsNotAHyperlink() {
    // a model id that is not part of any imported project and no explicit source: nothing to navigate to
    String line = "[WARNING] 'x' is missing. @ org.example:unknown:jar:9, line 2, column 3";
    assertNull(myFilter.applyFilter(line, line.length()));
  }

  private static void assertLineColumn(OpenFileHyperlinkInfo info, int expectedLine, int expectedColumn) {
    int[] actual = ReadAction.computeBlocking(() -> {
      OpenFileDescriptor descriptor = info.getDescriptor();
      assertNotNull(descriptor);
      if (descriptor.getLine() >= 0) {
        return new int[]{descriptor.getLine(), descriptor.getColumn()};
      }
      // the descriptor is offset-based once the document is loaded: map the offset back to line/column
      Document document = FileDocumentManager.getInstance().getDocument(descriptor.getFile());
      assertNotNull(document);
      int offset = descriptor.getOffset();
      int actualLine = document.getLineNumber(offset);
      return new int[]{actualLine, offset - document.getLineStartOffset(actualLine)};
    });
    assertEquals(expectedLine, actual[0]);
    assertEquals(expectedColumn, actual[1]);
  }

  private static Filter.ResultItem single(Filter.Result result) {
    assertEquals(1, result.getResultItems().size());
    return result.getResultItems().getFirst();
  }
}
