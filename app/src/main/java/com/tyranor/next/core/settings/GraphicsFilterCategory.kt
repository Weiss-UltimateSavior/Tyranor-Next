package com.tyranor.next.core.settings

import com.core.gl.GraphicsFilterDefinitions

/** Categories are derived from installed contributions and never serialized as nodes. */
data class GraphicsFilterCategory(val name: String, val presetIds: List<String>) {
    fun title(language: String): String = GraphicsFilterDefinitions.find(presetIds.first())!!.categoryTitle(language)
    companion object {
        val entries: List<GraphicsFilterCategory> = GraphicsFilterDefinitions.visible().groupBy { it.category }
            .map { (name, definitions) -> GraphicsFilterCategory(name, definitions.map { it.id }) }
    }
}

object GraphicsFilterCatalog {
    fun options(language: String): List<Pair<String, String>> = GraphicsFilterDefinitions.visible().map { it.id to it.title(language) }
    fun adjustable(id: String): Boolean = GraphicsFilterDefinitions.find(id)?.adjustable() == true
    fun sharpnessAdjustable(id: String): Boolean = GraphicsFilterDefinitions.find(id)?.sharpnessAdjustable == true
    fun scaleModes(id: String): List<GraphicsScaleMode> = GraphicsFilterDefinitions.find(id)?.scaleModes.orEmpty()
        .map { value -> GraphicsScaleMode.entries.first { it.wire == value } }
}
