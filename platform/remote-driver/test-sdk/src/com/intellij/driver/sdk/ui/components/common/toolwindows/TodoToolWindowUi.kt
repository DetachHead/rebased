package com.intellij.driver.sdk.ui.components.common.toolwindows

import com.intellij.driver.model.TreePathToRow
import com.intellij.driver.sdk.ui.DEFAULT_FIND_TIMEOUT
import com.intellij.driver.sdk.ui.accessibleName
import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.ContentTabLabelUi
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.contentTabLabel
import com.intellij.driver.sdk.ui.components.elements.setSelected
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.should
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitNotNull
import javax.swing.JList

/** Tab titles of the TODO tool window. */
object TodoTab {
  const val PROJECT: String = "Project"
  const val CURRENT_FILE: String = "Current File"
  const val SCOPE_BASED: String = "Scope Based"
}

/**
 * A TODO item of the tree.
 *
 * @property file project-relative path of the file with the item
 * @property line 1-based line of the item
 * @property caretColumn 1-based column of the TODO or FIXME marker, where navigation to the item places the caret
 * @property code the source line without leading whitespace
 */
data class TodoTreeItem(val file: String, val line: Int, val caretColumn: Int, val code: String) {
  /** The rendered tree row: the line number, a space, and [code]. */
  val rowText: String get() = "$line $code"
}

/**
 * The TODO tool window. Lookups are scoped to the tab that is showing, and nothing is looked up before it is used.
 * Files are addressed by project-relative paths, such as `src/org/example/Sample.java`.
 */
class TodoToolWindowUi(data: ComponentData) : ToolWindowUiComponent(data) {
  /** Titles of all tabs in display order, including unexpected ones. See [TodoTab]. */
  val availableTabs: List<String>
    get() = tabLabels().map { it.accessibleName.orEmpty() }

  val selectedTab: String
    get() = tabLabels().single { it.isSelected }.accessibleName.orEmpty()

  /** Clicks the tab and waits until it is selected. */
  fun selectTab(name: String) {
    contentTabLabel(name).click()
    should("TODO tab $name is selected") { selectedTab == name }
  }

  /** The tree of the tab that is showing. */
  val tree: JTreeUiComponent
    get() = panel.tree()

  /** Scopes of the Scope Based tab. */
  val availableScopes: List<String>
    get() = scopeComboBox.listValues().map { scopeName(it) }

  /** The scope selected in the Scope Based tab. */
  val selectedScope: String
    get() = scopeName(scopeComboBox.getSelectedItem())

  /** Selects the scope in the Scope Based tab and waits until it is selected. */
  fun selectScope(name: String) {
    scopeComboBox.selectItemContains(name)
    should("TODO scope $name is selected") { selectedScope == name }
  }

  /** The summary row, such as `Found 6 TODO items in 1 file`, or `null` when the tab has none, as Current File. */
  val summary: String?
    get() = tree.collectExpandedPaths().map { it.path.last() }.firstOrNull { it.startsWith(SUMMARY_PREFIX) }

  /**
   * The item count next to the file node, 0 when none is shown. The node does not need to be expanded,
   * but it must be visible: a file inside a collapsed summary fails.
   */
  fun fileItemCount(relativePath: String): Int = itemCount(fileNode(tree.collectExpandedPaths(), relativePath).path.last())

  /** The icons of the file node, such as a path that ends with `java.svg`. The node must be visible. */
  fun fileIcons(relativePath: String): List<String> =
    tree.collectIconsAtRow(fileNode(tree.collectExpandedPaths(), relativePath).row).map { it.toString() }

  /** Expands the summary, if the tab has one, and the file node, and waits until the items of the file are visible. */
  fun expandFile(relativePath: String) {
    should("TODO file $relativePath is expanded", DEFAULT_FIND_TIMEOUT,
           errorMessage = { "Expanded paths: ${tree.collectExpandedPathsAsStrings()}" }) {
      // While the tree is still loading, an attempt fails or returns false, and should retries it.
      tree.collectExpandedPaths().firstOrNull { it.path.last().startsWith(SUMMARY_PREFIX) }?.let { tree.fixture.expandRow(it.row) }
      val node = fileNode(tree.collectExpandedPaths(), relativePath)
      tree.fixture.expandRow(node.row)
      childrenOf(tree.collectExpandedPaths(), node).isNotEmpty()
    }
  }

  /**
   * The item rows of the file in display order. See [TodoTreeItem.rowText]. Call [expandFile] first: a collapsed
   * file node with items fails instead of returning an empty list. A file node without items returns an empty list.
   */
  fun readItemTexts(relativePath: String): List<String> {
    val paths = tree.collectExpandedPaths()
    val node = fileNode(paths, relativePath)
    val children = childrenOf(paths, node)
    if (children.isEmpty() && itemCount(node.path.last()) > 0) {
      error("TODO file node is collapsed: ${node.path.last()}; expanded paths: ${tree.collectExpandedPathsAsStrings()}")
    }
    return children.map { it.path.last() }
  }

  /** Texts of the selected rows. See [TodoTreeItem.rowText]. */
  fun selectedItemTexts(): List<String> = tree.collectSelectedPaths().map { it.path.last() }

  /** Clicks the item row. With Navigate with Single Click on, this also opens the source. */
  fun clickItem(item: TodoTreeItem) {
    tree.clickRow(itemRow(item))
  }

  /** Double-clicks the item row, which opens the source. */
  fun doubleClickItem(item: TodoTreeItem) {
    tree.doubleClickRow(itemRow(item))
  }

  val nextTodoButton: ActionButtonUi get() = toolbarButton("Next TODO")
  val previousTodoButton: ActionButtonUi get() = toolbarButton("Previous TODO")
  val filterButton: ActionButtonUi get() = toolbarButton("Filter TODO Items")

  /** The checked entry of the filter popup, `Show All` when no filter is set. Opens the popup and closes it again. */
  val selectedFilter: String
    get() {
      val list = openFilterPopup()
      try {
        val options = list.rawItems
        val checked = options.filterIndexed { index, _ -> list.collectIconsAtIndex(index).any { it.contains("checkmark") } }
        return checked.singleOrNull() ?: error("Expected one checked TODO filter; checked: $checked; options: $options")
      }
      finally {
        keyboard { escape() }
      }
    }

  /** Selects the entry of the filter popup. `Show All` clears the filter. */
  fun selectFilter(name: String) {
    val list = openFilterPopup()
    val options = list.rawItems
    val index = options.indexOf(name)
    if (index < 0) {
      keyboard { escape() }
      error("TODO filter $name is absent; options: $options")
    }
    list.clickItemAtIndex(index)
  }

  /**
   * Turns Preview Source on or off. It is on by default.
   * Project has its own setting, and Current File and Scope Based share one.
   */
  fun setPreviewEnabled(enabled: Boolean) {
    toolbarButton("Preview Source").setSelected(enabled)
  }

  /**
   * Turns Navigate with Single Click on or off. It is off by default.
   * Project has its own setting, and Current File and Scope Based share one.
   */
  fun setNavigateWithSingleClick(enabled: Boolean) {
    toolbarButton("Navigate with Single Click").setSelected(enabled)
  }

  /** The preview editor of the tab that is showing. */
  val preview: JEditorUiComponent
    get() = panel.x("TODO preview panel") { byType("com.intellij.usages.impl.UsagePreviewPanel") }
      .x(JEditorUiComponent::class.java, "TODO preview editor") { byType("com.intellij.openapi.editor.impl.EditorComponentImpl") }

  private val panel: UiComponent
    get() = waitNotNull("One TODO panel is showing", timeout = DEFAULT_FIND_TIMEOUT) {
      xx(UiComponent::class.java) { byType("com.intellij.ide.todo.TodoPanel") }.list().singleOrNull { it.component.isShowing() }
    }

  private val scopeComboBox: JComboBoxUiComponent
    get() = panel.comboBox { byAccessibleName("Scope") }

  private fun tabLabels(): List<ContentTabLabelUi> =
    xx(ContentTabLabelUi::class.java) { byType("com.intellij.openapi.wm.impl.content.ContentTabLabel") }.list()

  private fun toolbarButton(name: String): ActionButtonUi = panel.actionButton { byAccessibleName(name) }

  /**
   * Opens the filter popup, unless it is already open, and returns its list.
   * The list is found by its `Show All` entry, so other popups, such as tooltips, do not match.
   */
  private fun openFilterPopup(): JListUiComponent {
    val list = driver.ui.x(JListUiComponent::class.java, "TODO filter popup") {
      and(byType(JList::class.java), contains(byVisibleText(SHOW_ALL)))
    }
    if (!list.present()) filterButton.click()
    return list
  }

  private fun itemRow(item: TodoTreeItem): Int {
    tree.waitForNodesLoaded()
    val paths = tree.collectExpandedPaths()
    return childrenOf(paths, fileNode(paths, item.file)).singleOrNull { it.path.last() == item.rowText }?.row
           ?: error("TODO item $item is absent; expanded paths: ${tree.collectExpandedPathsAsStrings()}")
  }

  private fun fileNode(paths: List<TreePathToRow>, relativePath: String): TreePathToRow =
    paths.singleOrNull { fileLabelMatches(it.path.last(), relativePath) }
    ?: error("TODO file $relativePath is absent or the result is collapsed; expanded paths: ${tree.collectExpandedPathsAsStrings()}")

  private companion object {
    const val SUMMARY_PREFIX = "Found "
    const val SHOW_ALL = "Show All"
    val ITEM_COUNT = Regex("""(?<!\d)(\d+) items?\b""")

    fun childrenOf(paths: List<TreePathToRow>, node: TreePathToRow): List<TreePathToRow> =
      paths.filter { it.path.size == node.path.size + 1 && it.path.dropLast(1) == node.path }

    /**
     * Project and Current File label a file node with the file name, Scope Based with the absolute path of the project copy.
     * The item count next to the label is ignored.
     */
    fun fileLabelMatches(label: String, relativePath: String): Boolean {
      val shown = ITEM_COUNT.replace(label, "").trim().replace('\\', '/')
      return shown == relativePath.substringAfterLast('/') || shown.endsWith("/$relativePath")
    }

    fun itemCount(label: String): Int = ITEM_COUNT.find(label)?.groupValues?.get(1)?.toInt() ?: 0

    // The rendered scope text ends with the title of its group separator, for example "petclinic Other".
    fun scopeName(renderedText: String): String = renderedText.removeSuffix(" Other")
  }
}
