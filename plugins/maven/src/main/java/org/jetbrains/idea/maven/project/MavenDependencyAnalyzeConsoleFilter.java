// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.jetbrains.idea.maven.MavenDisposable;
import org.jetbrains.idea.maven.navigator.MavenNavigationUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns dependency coordinates printed by {@code dependency:analyze} and {@code dependency:analyze-only}
 * (for example {@code org.apache.commons:commons-lang3:jar:3.18.0:compile}) into hyperlinks that navigate to the
 * corresponding {@code <dependency>} declaration in the module's {@code pom.xml}.
 * <p>
 * The declaring module is taken from the goal header that precedes the report
 * ({@code --- dependency:3.7.0:analyze-only (default-cli) @ <module> ---}). When the coordinate is used but not
 * declared in that module (a "used undeclared" dependency) the link falls back to opening the module {@code pom.xml}
 * so the user still lands in the right file.
 *
 * @see <a href="https://youtrack.jetbrains.com/issue/IDEA-394030">IDEA-394030</a>
 */
public final class MavenDependencyAnalyzeConsoleFilter implements Filter {
  // "[INFO] --- dependency:3.7.0:analyze-only (default-cli) @ dependencies ---"
  // Whitespace quantifiers in the prefixes are possessive: filters see arbitrary terminal output, and backtracking
  // over long indentation makes the match time quadratic in the line length.
  private static final Pattern ANALYZE_HEADER_PATTERN = Pattern.compile(
    "^\\s*+(?:\\[[A-Z]+]\\s*+)?---\\s+\\S+:analyze(?:-only)?\\s+\\([^)]*\\)\\s+@\\s+(\\S+)\\s+---\\s*$");

  // "[ERROR]    org.apache.commons:commons-lang3:jar:3.18.0:compile"
  // groupId:artifactId:type[:classifier]:version:scope; type, classifier and version are any colon-free text
  // (versions may contain '+' and other punctuation), the scope keyword at the end keeps the pattern selective.
  private static final Pattern COORDINATE_PATTERN = Pattern.compile(
    "^\\s*+(?:\\[[A-Z]+]\\s*+)?" +
    "(([\\w.\\-]+):([\\w.\\-]+):[^:\\s]+(?::[^:\\s]+)?:[^:\\s]+:" +
    "(?:compile|provided|runtime|test|system|import))\\s*$");

  /** ArtifactId of the module whose analyze report is currently being read, taken from the preceding goal header line. */
  private volatile @Nullable String myCurrentModuleArtifactId;

  @Override
  public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
    Matcher headerMatcher = ANALYZE_HEADER_PATTERN.matcher(line);
    if (headerMatcher.find()) {
      myCurrentModuleArtifactId = headerMatcher.group(1);
      return null;
    }

    if (line.indexOf(':') < 0) return null;

    Matcher matcher = COORDINATE_PATTERN.matcher(line);
    if (!matcher.find()) return null;

    String groupId = matcher.group(2);
    String artifactId = matcher.group(3);

    int lineStart = entireLength - line.length();
    int highlightStart = lineStart + matcher.start(1);
    int highlightEnd = lineStart + matcher.end(1);

    return new Result(highlightStart, highlightEnd,
                      new DependencyHyperlinkInfo(groupId, artifactId, myCurrentModuleArtifactId));
  }

  /**
   * Navigates to the {@code <dependency>} declaration of {@code groupId:artifactId}.
   */
  @ApiStatus.Internal
  public static final class DependencyHyperlinkInfo implements HyperlinkInfo {
    private final @NotNull String myGroupId;
    private final @NotNull String myArtifactId;
    private final @Nullable String myModuleArtifactId;

    public DependencyHyperlinkInfo(@NotNull String groupId, @NotNull String artifactId, @Nullable String moduleArtifactId) {
      myGroupId = groupId;
      myArtifactId = artifactId;
      myModuleArtifactId = moduleArtifactId;
    }

    @Override
    public void navigate(@NotNull Project project) {
      ReadAction.nonBlocking(() -> findTargetNavigatable(project))
        .expireWith(MavenDisposable.getInstance(project))
        .finishOnUiThread(ModalityState.defaultModalityState(), navigatable -> {
          if (navigatable != null && navigatable.canNavigate()) {
            navigatable.navigate(true);
          }
        })
        .submit(AppExecutorUtil.getAppExecutorService());
    }

    @VisibleForTesting
    @RequiresReadLock
    public @Nullable Navigatable findTargetNavigatable(@NotNull Project project) {
      if (project.isDisposed()) return null;
      MavenProjectsManager manager = MavenProjectsManager.getInstance(project);

      VirtualFile modulePom = findModulePom(manager);
      if (modulePom != null) {
        Navigatable navigatable = MavenNavigationUtil.createNavigatableForDependency(project, modulePom, myGroupId, myArtifactId);
        if (navigatable.canNavigate()) return navigatable;
        // The coordinate is used but not declared in the reporting module ("used undeclared"): stay in that module's
        // pom.xml instead of jumping to an unrelated module that happens to declare the same dependency.
        return MavenNavigationUtil.createNavigatableForPom(project, modulePom);
      }

      for (MavenProject mavenProject : manager.getProjects()) {
        Navigatable navigatable = MavenNavigationUtil.createNavigatableForDependency(project, mavenProject.getFile(), myGroupId, myArtifactId);
        if (navigatable.canNavigate()) return navigatable;
      }

      return null;
    }

    private @Nullable VirtualFile findModulePom(@NotNull MavenProjectsManager manager) {
      if (myModuleArtifactId == null) return null;
      for (MavenProject mavenProject : manager.getProjects()) {
        if (myModuleArtifactId.equals(mavenProject.getMavenId().getArtifactId())) {
          return mavenProject.getFile();
        }
      }
      return null;
    }

    @VisibleForTesting
    public @NotNull String getGroupId() {
      return myGroupId;
    }

    @VisibleForTesting
    public @NotNull String getArtifactId() {
      return myArtifactId;
    }

    @VisibleForTesting
    public @Nullable String getModuleArtifactId() {
      return myModuleArtifactId;
    }
  }
}
