// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:ApiStatus.Experimental
package org.jetbrains.plugins.gradle.execution.test.runner

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.gradle.execution.GradleRunnerUtil
import org.jetbrains.plugins.gradle.execution.build.CachedModuleDataFinder
import org.jetbrains.plugins.gradle.execution.test.runner.GradleTestRunConfigurationProducer.findTestsTaskToRun
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.cmd.node.GradleCommandLine
import java.util.Collections

fun <E : PsiElement> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  testTasksToRun: List<Map<String, List<String>>>,
  sourceElements: Iterable<E>,
  createFilter: (E) -> String
): Boolean {
  return applyTestConfiguration(module, testTasksToRun, sourceElements, ::getSourceFile, createFilter)
}

fun <E : PsiElement> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  sourceElements: Iterable<E>,
  createFilter: (E) -> String
): Boolean {
  return applyTestConfiguration(module, sourceElements, ::getSourceFile, createFilter)
}

fun <E : PsiElement> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  testTasksToRun: List<Map<String, List<String>>>,
  vararg sourceElements: E,
  createFilter: (E) -> String
): Boolean {
  return applyTestConfiguration(module, testTasksToRun, sourceElements.asIterable(), createFilter)
}

fun <E : PsiElement> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  vararg sourceElements: E,
  createFilter: (E) -> String
): Boolean {
  return applyTestConfiguration(module, sourceElements.asIterable(), createFilter)
}

fun <T> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  testTasksToRun: List<Map<String, List<String>>>,
  tests: Iterable<T>,
  findTestSource: (T) -> VirtualFile?,
  createFilter: (T) -> String): Boolean {
  return applyTestConfiguration(module, tests, findTestSource, createFilter) { source ->
    testTasksToRun.mapNotNull { it[source.path] }
  }
}

fun <T> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  tests: Iterable<T>,
  findTestSource: (T) -> VirtualFile?,
  createFilter: (T) -> String): Boolean {
  return applyTestConfiguration(module, tests, findTestSource, createFilter) { source ->
    listOf(findTestsTaskToRun(source, module.project))
  }
}

fun <T> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  module: Module,
  tests: Iterable<T>,
  findTestSource: (T) -> VirtualFile?,
  createFilter: (T) -> String,
  getTestsTaskToRun: (VirtualFile) -> List<List<String>>
): Boolean {
  if (!GradleRunnerUtil.isGradleModule(module)) return false
  var projectPath = GradleRunnerUtil.resolveProjectPath(module) ?: return false
  CachedModuleDataFinder.getGradleModuleData(module)?.also {
    val isGradleProjectDirUsedToRunTasks = it.directoryToRunTask == it.gradleProjectDir
    if (!isGradleProjectDirUsedToRunTasks) {
      projectPath = it.directoryToRunTask
    }
  }
  return applyTestConfiguration(projectPath, tests, findTestSource, createFilter, getTestsTaskToRun)
}

fun <T> ExternalSystemTaskExecutionSettings.applyTestConfiguration(
  projectPath: String,
  tests: Iterable<T>,
  findTestSource: (T) -> VirtualFile?,
  createFilter: (T) -> String,
  getTestsTaskToRun: (VirtualFile) -> List<List<String>>
): Boolean {
  // escaped-tasks -> arguments
  val testRunConfigurations = LinkedHashMap<List<String>, MutableSet<String>>()
  for (test in tests) {
    val sourceFile = findTestSource(test) ?: return false
    for (tasks in getTestsTaskToRun(sourceFile)) {
      if (tasks.isEmpty()) continue
      val escapedTasks = tasks.map { it.escapeIfNeeded() }
      val arguments = testRunConfigurations.getOrPut(escapedTasks, ::LinkedHashSet)
      val testFilter = createFilter(test).trim()
      if (testFilter.isNotEmpty()) {
        arguments.add(testFilter)
      }
    }
  }

  if (testRunConfigurations.isEmpty()) {
    return false
  }

  externalProjectPath = projectPath
  taskNames = testRunConfigurations.entries.flatMap { it.key + it.value }
  if (testRunConfigurations.size > 1) {
    addScriptParameterIfAbsent(CONTINUE_OPTION)
  }

  return true
}

/**
 * Finds among [candidates] an existing run configuration equivalent to [selectedConfiguration]:
 * it runs in the same external project, and its Gradle tasks with arguments are consisted exactly
 * from the [selectedTaskTokens] groups (see [isConsistedFrom]), in any group order.
 */
@ApiStatus.Internal
fun findExistingConfigurationSettings(
  candidates: List<RunnerAndConfigurationSettings>,
  selectedConfiguration: GradleRunConfiguration,
  selectedTaskTokens: List<List<String>>,
): RunnerAndConfigurationSettings? {
  val externalProjectPath = selectedConfiguration.settings.externalProjectPath ?: return null
  if (selectedTaskTokens.isEmpty() || selectedTaskTokens.any { it.isEmpty() }) return null
  val selectedTokenCount = selectedTaskTokens.sumOf { it.size }
  return candidates.firstOrNull { settings ->
    val existingConfiguration = settings.configuration as? GradleRunConfiguration ?: return@firstOrNull false
    if (existingConfiguration === selectedConfiguration) return@firstOrNull false
    if (externalProjectPath != existingConfiguration.settings.externalProjectPath) return@firstOrNull false
    val existingTaskTokens = getNormalizedTaskTokens(existingConfiguration)
    existingTaskTokens.size == selectedTokenCount && isConsistedFrom(existingTaskTokens, selectedTaskTokens)
  }
}

/**
 * Task tokens of [configuration], re-tokenized from the joined command line: a task name entry may hold
 * several tokens (e.g. `--tests "TestCase.test1"` produced by [applyTestConfiguration]), and parsing the
 * task name list directly would keep such an entry as a single opaque token.
 */
@ApiStatus.Internal
fun getNormalizedTaskTokens(configuration: GradleRunConfiguration): List<String> {
  val commandLine = configuration.settings.taskNames.joinToString(" ")
  return GradleCommandLine.parse(commandLine).tasks.tokens
}

/**
 * Checks that [list] can be represented by sequence from all or part of [subLists].
 *
 * For example:
 *
 * `[1, 2, 3, 4] is not consisted from [1, 2]`
 *
 * `[1, 2, 3, 4] is consisted from [1, 2] and [3, 4]`
 *
 * `[1, 2, 3, 4] is consisted from [1, 2], [3, 4] and [1, 2, 3]`
 *
 * `[1, 2, 3, 4] is not consisted from [1, 2, 3] and [3, 4]`
 */
@ApiStatus.Internal
fun isConsistedFrom(list: List<String>, subLists: List<List<String>>): Boolean {
  val reducer = ArrayList<String?>(list)
  val sortedTiles = subLists.sortedByDescending { it.size }
  for (tile in sortedTiles) {
    val index = Collections.indexOfSubList(reducer, tile)
    if (index >= 0) {
      val subReducer = reducer.subList(index, index + tile.size)
      subReducer.clear()
      subReducer.add(null)
    }
  }
  return reducer.all { it == null }
}

@ApiStatus.Internal
fun ExternalSystemTaskExecutionSettings.addScriptParameterIfAbsent(option: String) {
  val parameters = scriptParameters?.trim() ?: ""
  if (parameters.isEmpty()) {
    scriptParameters = option
    return
  }
  if (option in ParametersListUtil.parse(parameters)) {
    return
  }
  scriptParameters = "$parameters $option"
}

/**
 * The Gradle option that makes all chosen test tasks run even if one of them fails.
 */
internal const val CONTINUE_OPTION: String = "--continue"

fun String.escapeIfNeeded(): String = when {
  contains(' ') -> "'$this'"
  else -> this
}

fun getSourceFile(sourceElement: PsiElement?): VirtualFile? {
  if (sourceElement == null) return null
  if (sourceElement is PsiFileSystemItem) {
    return sourceElement.virtualFile
  }
  val containingFile = sourceElement.containingFile
  if (containingFile != null) {
    return containingFile.virtualFile
  }
  return null
}
