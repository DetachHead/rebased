// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.execution.test.runner

import com.intellij.execution.JavaRunConfigurationExtensionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.gradle.execution.test.runner.TestTasksChooser.Companion.contextWithLocationName
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.TasksToRun
import org.jetbrains.plugins.gradle.util.cmd.node.GradleCommandLine.Companion.parse
import org.jetbrains.plugins.gradle.util.cmd.node.GradleCommandLineTasks
import org.jetbrains.plugins.gradle.util.createTestWildcardFilter
import java.util.StringJoiner
import java.util.function.Consumer

abstract class AbstractGradleTestRunConfigurationProducer<E : PsiElement, Ex : PsiElement> : GradleTestRunConfigurationProducer() {

  protected abstract fun getElement(context: ConfigurationContext): E?

  protected abstract fun getLocationName(context: ConfigurationContext, element: E): String

  protected abstract fun suggestConfigurationName(context: ConfigurationContext, element: E, chosenElements: List<Ex>): String

  protected open fun suggestTaskFirstConfigurationName(
    context: ConfigurationContext,
    element: E,
    chosenElements: List<Ex>,
    selectedTestTaskNames: List<String>,
  ): String = createTaskFirstConfigurationNameFor(selectedTestTaskNames, listOf(suggestConfigurationName(context, element, chosenElements)))

  protected abstract fun chooseSourceElements(context: ConfigurationContext, element: E, onElementsChosen: Consumer<List<Ex>>)

  private fun chooseSourceElements(context: ConfigurationContext, element: E, onElementsChosen: (List<Ex>) -> Unit) {
    chooseSourceElements(context, element, Consumer { onElementsChosen(it) })
  }

  protected abstract fun getAllTestsTaskToRun(context: ConfigurationContext, element: E, chosenElements: List<Ex>): List<TestTasksToRun>

  /** Whether [onFirstRun] uses [testTasksChooser] to select among the available test tasks. */
  protected open fun usesBaseTestTasksChooser(): Boolean = true

  /**
   * Whether the test task selection is postponed to [onFirstRun], and therefore an existing configuration cannot be
   * resolved from [context] alone. Producers that choose the test tasks themselves opt out of this by overriding
   * [usesBaseTestTasksChooser], and keep the plain existing configuration lookup.
   */
  private fun shouldDeferTestTaskSelection(context: ConfigurationContext): Boolean {
    if (!usesBaseTestTasksChooser()) return false
    // Without a module the test tasks cannot be resolved (see getAllTestsTaskToRun). Some producers
    // build a module-less context (e.g. the JS/Node test-run producers), so fall back to the plain
    // existing configuration lookup instead of failing here (IDEA-384446).
    if (context.module == null) return false
    val element = getElement(context) ?: return false
    return allTestsTaskToRun(context, element)
             .map { it.tasksToRun.testName }
             .toSet()
             .size > 1
  }

  override fun findOrCreateConfigurationFromContext(context: ConfigurationContext): ConfigurationFromContext? {
    val configurationFromContext = super.findOrCreateConfigurationFromContext(context) ?: return null
    if (!shouldDeferTestTaskSelection(context)) return configurationFromContext
    val element = getElement(context) ?: return configurationFromContext
    // super created a new configuration: the deferred lookup hid the stored twin that onFirstRun usually reuses.
    // setUniqueNameIfNeeded saw the twin, so the gutter menu would show "Run 'MyTest (1)'".
    // Restore the plain name here. onFirstRun sets the final name once the test tasks are chosen.
    (configurationFromContext.configuration as GradleRunConfiguration).name =
      suggestConfigurationName(context, element, emptyList())
    return configurationFromContext
  }

  private fun getAllTasksAndArguments(context: ConfigurationContext, element: E, chosenElements: List<Ex>): List<GradleCommandLineTasks> {
    return getAllTestsTaskToRun(context, element, chosenElements)
      .map { it.toTasksAndArguments() }
  }

  private fun allTestsTaskToRun(context: ConfigurationContext, element: E): List<TestTasksToRun> {
    return getAllTestsTaskToRun(context, element, emptyList())
  }

  override fun findExistingConfiguration(context: ConfigurationContext): RunnerAndConfigurationSettings? {
    if (shouldDeferTestTaskSelection(context)) {
      return null
    }
    return super.findExistingConfiguration(context)
  }

  override fun doSetupConfigurationFromContext(
    configuration: GradleRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>,
  ): Boolean {
    val project = context.project ?: return false
    val module = context.module ?: return false
    val externalProjectPath = resolveProjectPath(module) ?: return false
    val location = context.location ?: return false
    val element = getElement(context) ?: return false
    val allTasksAndArguments = getAllTasksAndArguments(context, element, emptyList())
    val tasksAndArguments = allTasksAndArguments.firstOrNull() ?: return false

    sourceElement.set(element)
    configuration.name = suggestConfigurationName(context, element, emptyList())
    setUniqueNameIfNeeded(project, configuration)
    configuration.settings.externalProjectPath = externalProjectPath
    configuration.settings.taskNames = tasksAndArguments.tokens

    JavaRunConfigurationExtensionManager.instance.extendCreatedConfiguration(configuration, location)
    return true
  }

  override fun doIsConfigurationFromContext(
    configuration: GradleRunConfiguration,
    context: ConfigurationContext,
  ): Boolean {
    val module = context.module ?: return false
    val externalProjectPath = resolveProjectPath(module) ?: return false
    val element = getElement(context) ?: return false
    val allTestsTaskToRun = allTestsTaskToRun(context, element)
    val allTasksAndArguments = allTestsTaskToRun.map { it.toTasksAndArguments() }
    val tasksAndArguments = configuration.commandLine.tasks.tokens
    return externalProjectPath == configuration.settings.externalProjectPath &&
           tasksAndArguments.isNotEmpty() && allTasksAndArguments.isNotEmpty() &&
           isConsistedFrom(tasksAndArguments, allTasksAndArguments.map { it.tokens })
  }

  override fun onFirstRun(configuration: ConfigurationFromContext, context: ConfigurationContext, startRunnable: Runnable) {
    val project = context.project
    val element = getElement(context)
    if (project == null || element == null) {
      LOG.warn("Cannot extract configuration data from context, uses raw run configuration")
      super.onFirstRun(configuration, context, startRunnable)
      return
    }
    // [findExistingConfiguration] skipped the existing configuration lookup, so it has to be redone below, once the
    // test tasks are known. Otherwise it already ran, and repeating it here would be wrong: the lookup below matches
    // on task tokens alone, and so ignores the constraints a producer declares in [doIsConfigurationFromContext].
    val canReuseExistingConfiguration = shouldDeferTestTaskSelection(context)
    val runConfiguration = configuration.configuration as GradleRunConfiguration
    val dataContext = contextWithLocationName(context.dataContext, getLocationName(context, element))
    chooseSourceElements(context, element) { elements ->
      val allTestsToRun = getAllTestsTaskToRun(context, element, elements)
        .groupBy { it.tasksToRun.testName }
        .mapValues { it.value }
      val hasMultipleTestTasks = allTestsToRun.size > 1
      testTasksChooser.chooseTestTasks(project, dataContext, allTestsToRun) { chosenTestsToRun ->
        val chosenTasksAndArguments = chosenTestsToRun.flatten()
          .groupBy { it.tasksToRun }
          .mapValues { it.value.map(TestTasksToRun::testFilter).toSet() }
          .map { createTasksAndArguments(it.key, it.value) }

        val existingConfiguration = when {
          canReuseExistingConfiguration -> findExistingConfigurationSettings(
            getConfigurationSettingsList(RunManager.getInstance(project)),
            runConfiguration,
            chosenTasksAndArguments.map { it.tokens }
          )
          else -> null
        }
        if (existingConfiguration != null) {
          configuration.configurationSettings = existingConfiguration
        }
        else {
          runConfiguration.settings.taskNames = chosenTasksAndArguments.flatMap { it.tokens }
          if (chosenTasksAndArguments.size > 1) {
            runConfiguration.settings.addScriptParameterIfAbsent(CONTINUE_OPTION)
          }

          val selectedTestTaskNames = chosenTestsToRun.flatten()
            .map { it.tasksToRun.testName }
            .distinct()

          runConfiguration.name = if (hasMultipleTestTasks) {
            suggestTaskFirstConfigurationName(context, element, elements, selectedTestTaskNames)
          } else {
            suggestConfigurationName(context, element, elements)
          }
          setUniqueNameIfNeeded(project, runConfiguration)
        }

        super.onFirstRun(configuration, context, startRunnable)
      }
    }
  }

  private fun createTasksAndArguments(tasksToRun: TasksToRun, testFilters: Collection<String>): GradleCommandLineTasks {
    val commandLineBuilder = StringJoiner(" ")
    for (task in tasksToRun) {
      commandLineBuilder.add(task.escapeIfNeeded())
    }
    if (createTestWildcardFilter() !in testFilters) {
      for (testFilter in testFilters) {
        if (StringUtil.isNotEmpty(testFilter)) {
          commandLineBuilder.add(testFilter)
        }
      }
    }
    val commandLine = commandLineBuilder.toString()
    return parse(commandLine).tasks
  }

  private fun TestTasksToRun.toTasksAndArguments() = createTasksAndArguments(tasksToRun, listOf(testFilter))

  class TestTasksToRun(val tasksToRun: TasksToRun, val testFilter: String)
}
