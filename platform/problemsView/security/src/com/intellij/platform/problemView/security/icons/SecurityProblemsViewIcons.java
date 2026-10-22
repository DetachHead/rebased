package com.intellij.platform.problemView.security.icons;

import com.intellij.ui.IconManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * NOTE THIS FILE IS AUTO-GENERATED
 * DO NOT EDIT IT BY HAND, run "Generate icon classes" configuration instead
 */
public final class SecurityProblemsViewIcons {
  private static @NotNull Icon load(@NotNull String path, int cacheKey, int flags) {
    return IconManager.getInstance().loadRasterizedIcon(path, SecurityProblemsViewIcons.class.getClassLoader(), cacheKey, flags);
  }
  private static @NotNull Icon load(@NotNull String expUIPath, @NotNull String path, int cacheKey, int flags) {
    return IconManager.getInstance().loadRasterizedIcon(path, expUIPath, SecurityProblemsViewIcons.class.getClassLoader(), cacheKey, flags);
  }

  public static final class SeverityEmpty {
    /** 16x17 */ public static final @NotNull Icon Highall_tree_outline = load("icons/expui/severity-empty/highAllTreeOutline.svg", "icons/severity-empty/highall_tree_outline.svg", 1102551749, 0);
    /** 16x17 */ public static final @NotNull Icon Lowall_tree_outline = load("icons/expui/severity-empty/lowAllTreeOutline.svg", "icons/severity-empty/lowall_tree_outline.svg", 1024409186, 0);
    /** 16x17 */ public static final @NotNull Icon Medall_tree_outline = load("icons/expui/severity-empty/mediumAllTreeOutline.svg", "icons/severity-empty/medall_tree_outline.svg", 893664948, 0);
    /** 16x16 */ public static final @NotNull Icon SafeAllTreeOutline = load("icons/expui/severity-empty/safeAllTreeOutline.svg", 164925506, 2);
    /** 16x16 */ public static final @NotNull Icon UncheckedAllTreeOutline = load("icons/expui/severity-empty/uncheckedAllTreeOutline.svg", -418077987, 2);
  }

  public static final class Severity {
    /** 23x23 */ public static final @NotNull Icon Highall = load("icons/expui/severity/highAll.svg", "icons/severity/highall.svg", -1862965573, 0);
    /** 16x16 */ public static final @NotNull Icon Highall_tree = load("icons/expui/severity/highAllTree.svg", "icons/severity/highall_tree.svg", -782706579, 0);
    /** 23x23 */ public static final @NotNull Icon Lowall = load("icons/expui/severity/lowAll.svg", "icons/severity/lowall.svg", -109728571, 2);
    /** 16x16 */ public static final @NotNull Icon Lowall_tree = load("icons/expui/severity/lowAllTree.svg", "icons/severity/lowall_tree.svg", -905704210, 2);
    /** 23x23 */ public static final @NotNull Icon Medall = load("icons/expui/severity/mediumAll.svg", "icons/severity/medall.svg", 458702419, 0);
    /** 16x16 */ public static final @NotNull Icon Medall_tree = load("icons/expui/severity/mediumAllTree.svg", "icons/severity/medall_tree.svg", -1367224275, 0);
    /** 24x24 */ public static final @NotNull Icon SafeAll = load("icons/expui/severity/safeAll.svg", 1498821751, 2);
    /** 16x16 */ public static final @NotNull Icon SafeAllTree = load("icons/expui/severity/safeAllTree.svg", -1606364416, 2);
    /** 24x24 */ public static final @NotNull Icon UncheckedAll = load("icons/expui/severity/uncheckedAll.svg", -1713147470, 2);
    /** 16x16 */ public static final @NotNull Icon UncheckedAllTree = load("icons/expui/severity/uncheckedAllTree.svg", -1974724441, 2);
  }

  public static final class Status {
    /** 16x16 */ public static final @NotNull Icon IssuesFound = load("icons/expui/status/issuesFound.svg", 752487729, 0);
    /** 16x16 */ public static final @NotNull Icon NoIssuesFound = load("icons/expui/status/noIssuesFound.svg", -1443816160, 0);
  }
}
