// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.packaging.management

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.jetbrains.python.packaging.PyPackageName
import com.jetbrains.python.getOrNull
import com.jetbrains.python.packaging.common.PythonPackage
import com.jetbrains.python.packaging.common.PythonPackageMetadata
import com.jetbrains.python.packaging.packageRequirements.DependencyTreeProvider
import com.jetbrains.python.packaging.packageRequirements.PackageTreeNode
import java.util.IdentityHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting

/**
 * Installed dependency graph as [PackageTreeNode]s versioned by the resolved installed version,
 * not by a declared constraint.
 *
 * Without a [DependencyTreeProvider] — pip, and so every plain-Python SDK — there is no `show --tree` to ask,
 * and the edges come from the installed distributions' Core Metadata instead. Dropping them made the graph
 * depend on the SDK flavor rather than on the environment.
 */
@ApiStatus.Internal
@RequiresBackgroundThread(generateAssertion = false /* IJPL-115548 */)
suspend fun PythonPackageManager.installedDependencyGraph(): List<PackageTreeNode> {
  val installed = listInstalledPackages()
  val provider = treeProvider ?: return metadataDependencyGraph(installed, awaitInstalledPackagesMetadata())
  // `<manager> show --tree` prints version *constraints* for transitive nodes (e.g. `urllib3 >=1.21.1,<1.24`)
  // rather than the resolved installed version, so re-key every node to the concrete installed version.
  val installedVersions = installed.associate { PyPackageName.normalizePackageName(it.name) to it.version }
  val copies = IdentityHashMap<PackageTreeNode, PackageTreeNode>()
  return provider.getDependencyTrees().getOrNull().orEmpty().map { it.withResolvedVersions(installedVersions, copies) }
}

@VisibleForTesting
@ApiStatus.Internal
fun metadataDependencyGraph(
  installed: List<PythonPackage>,
  metadata: Map<PyPackageName, PythonPackageMetadata>,
): List<PackageTreeNode> {
  val nodes = LinkedHashMap<String, PackageTreeNode>()
  installed.forEach { pkg ->
    nodes.putIfAbsent(PyPackageName.normalizePackageName(pkg.name), PackageTreeNode(PyPackageName.from(pkg.name), version = pkg.version))
  }

  // Linking is a second pass over nodes that all exist already, so a cycle — and Python has them — just closes.
  nodes.forEach { (normalizedName, node) ->
    metadata[PyPackageName.from(normalizedName)]?.requiresDist.orEmpty()
      .mapTo(LinkedHashSet()) { requiredName(it) }
      .forEach { requiredName ->
        if (requiredName == normalizedName) return@forEach
        nodes[requiredName]?.let { node.children.add(it) }
      }
  }

  return nodes.values.toList()
}

private fun requiredName(requiresDist: String): String =
  PyPackageName.normalizePackageName(requiresDist.trimStart().takeWhile { it.isLetterOrDigit() || it in "._-" })

/** Non-blocking flat snapshot (no edges) from the cached list; empty until seeded. */
@ApiStatus.Internal
fun PythonPackageManager.installedDependencyGraphSnapshot(): List<PackageTreeNode> =
  listInstalledPackagesSnapshot().map { PackageTreeNode(PyPackageName.from(it.name), version = it.version) }

/** Copies each node once, keeping the sharing, so a graph with a cycle ends. */
private fun PackageTreeNode.withResolvedVersions(
  installedVersions: Map<String, String>,
  copies: MutableMap<PackageTreeNode, PackageTreeNode>,
): PackageTreeNode {
  copies[this]?.let { return it }
  val copy = PackageTreeNode(name, mutableListOf(), group, installedVersions[PyPackageName.normalizePackageName(name.name)] ?: version)
  copies[this] = copy
  children.mapTo(copy.children) { it.withResolvedVersions(installedVersions, copies) }
  return copy
}
