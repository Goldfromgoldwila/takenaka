/*
 * This file is part of takenaka, licensed under the Apache License, Version 2.0 (the "License").
 *
 * Copyright (c) 2023 Matous Kucera
 *
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.kcra.takenaka.core.test.mapping.resolve

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import me.kcra.takenaka.core.*
import me.kcra.takenaka.core.mapping.ancestry.VersionedMappings
import me.kcra.takenaka.core.mapping.resolve.*
import net.fabricmc.mappingio.format.Tiny1Writer
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test

val VERSIONS = listOf(
    "1.19.3",
    "1.19.2",
    /*"1.19.1",
    "1.19",
    "1.18.2",
    "1.18.1",
    "1.18",
    "1.17.1",
    "1.17",
    "1.16.5",
    "1.16.4",
    "1.16.3",
    "1.16.2",
    "1.16.1",
    "1.15.2",
    "1.15.1",
    "1.15",
    "1.14.4",
    "1.14.3",
    "1.14.2",
    "1.14.1",
    "1.14",
    "1.13.2",
    "1.13.1",
    "1.13",
    "1.12.2",
    "1.12.1",
    "1.12",
    "1.11.2",
    "1.11.1",
    "1.11",
    "1.10.2",
    "1.10",
    "1.9.4",
    "1.9.2",
    "1.9",
    "1.8.8"*/
)

class MappingResolverTest {
    private val objectMapper = manifestObjectMapper()
    private val workspaceDir = File("test-workspace")

    @Test
    fun `resolve mappings for supported versions`() {
        val workspace = CompositeWorkspace(workspaceDir, resolverOptionsOf(RELAXED_CACHE))

        val time = measureTimeMillis {
            workspace.resolveMappings(objectMapper)
        }
        val cachedTime = measureTimeMillis {
            workspace.resolveMappings(objectMapper)
        }

        println("Elapsed ${time / 1000}s, cached ${cachedTime / 1000}s")
    }
}

suspend fun VersionedWorkspace.resolveVersionMappings(objectMapper: ObjectMapper): MemoryMappingTree = coroutineScope {
    val resolvers = listOf(
        MojangServerMappingResolver(this@resolveVersionMappings, objectMapper),
        IntermediaryMappingResolver(this@resolveVersionMappings),
        SeargeMappingResolver(this@resolveVersionMappings),
        SpigotClassMappingResolver(this@resolveVersionMappings, objectMapper),
        SpigotMemberMappingResolver(this@resolveVersionMappings, objectMapper),
        VanillaMappingContributor(this@resolveVersionMappings, objectMapper)
    )

    val file = MemoryMappingTree()

    resolvers.forEach {
        it.accept(file)
    }

    return@coroutineScope file
}

fun CompositeWorkspace.resolveMappings(objectMapper: ObjectMapper, save: Boolean = true): VersionedMappings = runBlocking {
    val manifest = objectMapper.versionManifest()
    val jobs = mutableListOf<Deferred<Pair<Version, MemoryMappingTree>>>()

    VERSIONS.forEach {
        val version = manifest[it] ?: error("did not find $it in manifest")

        jobs += async {
            val workspace = versioned(version)
            val tree = workspace.resolveVersionMappings(objectMapper)

            if (save) {
                tree.accept(MissingDescriptorFilter(Tiny1Writer(workspace["joined.tiny"].writer())))
            }

            version to tree
        }
    }

    return@runBlocking jobs.awaitAll().toMap()
}
