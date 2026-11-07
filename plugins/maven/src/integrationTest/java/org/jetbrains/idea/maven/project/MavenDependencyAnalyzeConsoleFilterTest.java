// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project;

import com.intellij.execution.filters.Filter;
import org.jetbrains.idea.maven.project.MavenDependencyAnalyzeConsoleFilter.DependencyHyperlinkInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing/highlighting tests for {@link MavenDependencyAnalyzeConsoleFilter} (IDEA-394030). These do not need an
 * imported Maven project: they verify which spans become hyperlinks and what coordinate/module each link captures.
 */
public class MavenDependencyAnalyzeConsoleFilterTest {

  private MavenDependencyAnalyzeConsoleFilter myFilter;

  @BeforeEach
  public void setUp() {
    myFilter = new MavenDependencyAnalyzeConsoleFilter();
  }

  @Test
  public void testHighlightsCoordinateWithErrorPrefix() {
    String coordinate = "org.apache.commons:commons-lang3:jar:3.18.0:compile";
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    " + coordinate, coordinate);
    assertEquals("org.apache.commons", info.getGroupId());
    assertEquals("commons-lang3", info.getArtifactId());
  }

  @Test
  public void testHighlightsCoordinateWithWarningPrefix() {
    String coordinate = "org.slf4j:slf4j-api:jar:2.0.9:compile";
    DependencyHyperlinkInfo info = assertSingleLink("[WARNING]    " + coordinate, coordinate);
    assertEquals("org.slf4j", info.getGroupId());
    assertEquals("slf4j-api", info.getArtifactId());
  }

  @Test
  public void testHighlightsCoordinateWithoutPrefix() {
    String coordinate = "com.google.guava:guava:jar:33.0.0-jre:compile";
    DependencyHyperlinkInfo info = assertSingleLink("   " + coordinate, coordinate);
    assertEquals("com.google.guava", info.getGroupId());
    assertEquals("guava", info.getArtifactId());
  }

  @Test
  public void testHighlightsCoordinateWithClassifier() {
    String coordinate = "org.example:demo:test-jar:tests:1.0.0:test";
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    " + coordinate, coordinate);
    assertEquals("org.example", info.getGroupId());
    assertEquals("demo", info.getArtifactId());
  }

  @Test
  public void testHighlightsVersionWithBuildMetadata() {
    String coordinate = "org.example:demo:jar:1.0+build:compile";
    DependencyHyperlinkInfo info = assertSingleLink("[WARNING]    " + coordinate, coordinate);
    assertEquals("org.example", info.getGroupId());
    assertEquals("demo", info.getArtifactId());
  }

  @Test
  public void testLongIndentationIsRejectedInLinearTime() {
    // Regression for a quadratic-backtracking pattern: this line took minutes to reject before the whitespace
    // quantifiers became possessive. The generous bound only guards against the quadratic behavior returning.
    String line = " ".repeat(65536) + "message: not a dependency";
    long startNanos = System.nanoTime();
    assertNoLink(line);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
    assertTrue(elapsedMillis < 10_000, "Rejecting a long all-whitespace prefix took " + elapsedMillis + " ms");
  }

  @Test
  public void testModuleTrackingFromAnalyzeOnlyHeader() {
    assertNoLink("[INFO] --- dependency:3.7.0:analyze-only (default-cli) @ my-module ---");
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    assertEquals("my-module", info.getModuleArtifactId());
  }

  @Test
  public void testModuleTrackingFromPluginPrefixedAnalyzeHeader() {
    assertNoLink("[INFO] --- maven-dependency-plugin:3.7.0:analyze (default-cli) @ core-module ---");
    DependencyHyperlinkInfo info = assertSingleLink("[WARNING]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    assertEquals("core-module", info.getModuleArtifactId());
  }

  @Test
  public void testModuleIsNullBeforeAnyHeader() {
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    assertNull(info.getModuleArtifactId());
  }

  @Test
  public void testOffsetsAccountForTerminalEntireLength() {
    String coordinate = "org.apache.commons:commons-lang3:jar:3.18.0:compile";
    String line = "[ERROR]    " + coordinate;
    // Emulate the built-in terminal, where the flushed buffer is longer than the current line.
    int precedingOutput = 4096;
    int entireLength = precedingOutput + line.length();

    Filter.Result result = myFilter.applyFilter(line, entireLength);
    assertNotNull(result);
    Filter.ResultItem item = result.getResultItems().getFirst();
    assertEquals(precedingOutput + line.indexOf(coordinate), item.getHighlightStartOffset());
    assertEquals(precedingOutput + line.indexOf(coordinate) + coordinate.length(), item.getHighlightEndOffset());
  }

  @Test
  public void testAnalyzeHeaderProducesNoLink() {
    assertNoLink("[INFO] --- dependency:3.7.0:analyze-only (default-cli) @ my-module ---");
  }

  @Test
  public void testPlainTextProducesNoLink() {
    assertNoLink("[INFO] Building my-module 1.0.0");
    assertNoLink("[INFO] BUILD SUCCESS");
  }

  @Test
  public void testLineWithColonButNotCoordinateProducesNoLink() {
    assertNoLink("[INFO] Total time:  3.456 s");
    assertNoLink("[INFO] Finished at: 2026-09-21T12:00:00+02:00");
  }

  @Test
  public void testUnknownScopeProducesNoLink() {
    assertNoLink("[ERROR]    org.example:demo:jar:1.0.0:bogus");
  }

  @Test
  public void testTooFewSegmentsProduceNoLink() {
    assertNoLink("[ERROR]    org.example:demo:compile");
  }

  private DependencyHyperlinkInfo assertSingleLink(String line, String expectedCoordinate) {
    Filter.Result result = myFilter.applyFilter(line, line.length());
    assertNotNull(result, "Expected a hyperlink for: " + line);
    List<Filter.ResultItem> items = result.getResultItems();
    assertEquals(1, items.size());
    Filter.ResultItem item = items.getFirst();
    int start = item.getHighlightStartOffset();
    int end = item.getHighlightEndOffset();
    assertEquals(expectedCoordinate, line.substring(start, end), "Highlight should cover exactly the coordinate");
    return assertInstanceOf(DependencyHyperlinkInfo.class, item.getHyperlinkInfo());
  }

  private void assertNoLink(String line) {
    assertNull(myFilter.applyFilter(line, line.length()), "Did not expect a hyperlink for: " + line);
  }
}
