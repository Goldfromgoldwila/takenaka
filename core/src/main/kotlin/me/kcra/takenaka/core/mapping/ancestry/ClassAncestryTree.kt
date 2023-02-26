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

package me.kcra.takenaka.core.mapping.ancestry

import me.kcra.takenaka.core.mapping.VersionedMappingMap
import net.fabricmc.mappingio.tree.MappingTree

typealias ClassAncestryTree = AncestryTree<MappingTree.ClassMapping, ElementKey>
typealias ClassAncestryNode = AncestryTree<MappingTree.ClassMapping, ElementKey>.Node

/**
 * Computes an ancestry tree of all classes in the supplied versions.
 *
 * @param mappings the joined version mapping files
 * @param allowedNamespaces namespaces that are used in this tree for tracing history, not distinguished by version; empty if all namespaces should be considered
 * @return the ancestry tree
 */
fun classAncestryTreeOf(mappings: VersionedMappingMap, allowedNamespaces: List<String> = emptyList()): ClassAncestryTree {
    val classTree = ClassAncestryTree()

    mappings.forEach { (version, tree) ->
        val treeAllowedNamespaces = tree.collectAllowedNamespaces(allowedNamespaces)
        classTree.allowedNamespaces[version] = treeAllowedNamespaces

        tree.classes.forEach { klass ->
            val classMappings = treeAllowedNamespaces.mapNotNull(klass::getDstName)
            // do we have a node with at least one same key?
            // if we don't, we make a new node and append it to the tree
            val node = classTree[classMappings] ?: classTree.Node().also { classTree += it }

            // append all mappings to the keys, ignoring duplicates (it's a set), and add a mapping entry to the node
            node.keys += classMappings
            node.mappings[version] = klass
        }
    }

    return classTree
}
