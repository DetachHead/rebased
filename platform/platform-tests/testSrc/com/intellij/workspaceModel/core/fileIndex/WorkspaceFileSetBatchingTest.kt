// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.core.fileIndex

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.testFramework.workspaceModel.update
import com.intellij.util.indexing.testEntities.IndexingTestEntity
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileIndexImpl
import com.intellij.workspaceModel.ide.NonPersistentEntitySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The index registers the file sets of one root in batches, so the result must not depend on where a batch ends.
 */
@TestApplication
class WorkspaceFileSetBatchingTest {
  @JvmField
  @RegisterExtension
  val projectModel: ProjectModelExtension = ProjectModelExtension()

  private val fileIndex get() = WorkspaceFileIndex.getInstance(projectModel.project)
  private val workspaceModel get() = WorkspaceModel.getInstance(projectModel.project)

  private lateinit var sharedRoot: VirtualFile
  private lateinit var sharedRootUrl: VirtualFileUrl

  @BeforeEach
  fun setUp() {
    sharedRoot = projectModel.baseProjectDir.newVirtualDirectory("shared")
    sharedRootUrl = workspaceModel.getVirtualFileUrlManager().storeAndGet(sharedRoot.url)
    WorkspaceFileIndexImpl.EP_NAME.point.registerExtension(NamedFileSetContributor(sharedRootUrl),
                                                           projectModel.disposableRule.disposable)
  }

  @Test
  fun `file sets of one root keep the registration order`(): Unit = timeoutRunBlocking {
    addEntity("a", "b", "c")
    assertFileSetNames("a", "b", "c")
  }

  @Test
  fun `the order does not depend on where the batch ends`(): Unit = timeoutRunBlocking {
    // two file sets make a TwoWorkspaceFileSets, and the third one used to jump in front of them
    addEntity("a", "b")
    addEntity("c")
    assertFileSetNames("a", "b", "c")
  }

  @Test
  fun `removing one entity keeps the file sets of the others`(): Unit = timeoutRunBlocking {
    addEntity("a")
    addEntity("b")
    addEntity("c")
    workspaceModel.update { storage ->
      storage.removeEntity(storage.entities(IndexingTestEntity::class.java).single { it.names == listOf("b") })
    }
    assertFileSetNames("a", "c")
  }

  @Test
  fun `many file sets of one root are all registered`(): Unit = timeoutRunBlocking {
    val names = List(200) { "n%03d".format(it) }
    addEntity(*names.toTypedArray())
    assertFileSetNames(*names.toTypedArray())
  }

  private suspend fun addEntity(vararg names: String) {
    workspaceModel.update { storage ->
      storage.addEntity(IndexingTestEntity(names.map { sharedRootUrl.append(it) }, emptyList(), NonPersistentEntitySource))
    }
  }

  private suspend fun assertFileSetNames(vararg expected: String) {
    val actual = readAction {
      fileIndex.findFileSetsWithCustomData(sharedRoot, true, true, false, false, false, false, false, NameData::class.java)
        .map { it.data.name }
    }
    assertEquals(expected.toList(), actual)
  }
}

/** The roots of the entity only carry names; the contributor registers a file set for each of them at one shared root. */
private val IndexingTestEntity.names: List<String> get() = roots.map { it.fileName }

private class NamedFileSetContributor(private val root: VirtualFileUrl) : WorkspaceFileIndexContributor<IndexingTestEntity> {
  override val entityClass: Class<IndexingTestEntity> get() = IndexingTestEntity::class.java

  override fun registerFileSets(entity: IndexingTestEntity, registrar: WorkspaceFileSetRegistrar, storage: EntityStorage) {
    for (name in entity.names) {
      registrar.registerFileSet(root, WorkspaceFileKind.CONTENT, entity, NameData(name))
    }
  }
}

private data class NameData(val name: String) : WorkspaceFileSetData
