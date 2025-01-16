package dev.kikugie.hall_of_fame.search

import com.github.ajalt.mordant.rendering.TextStyle
import dev.kikugie.hall_of_fame.indentLines
import dev.kikugie.hall_of_fame.spaceWords
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

typealias SearchID = String

@Serializable
data class SearchEntry(
    var id: SearchID,
    var valid: Boolean = true,
    var name: Configurable = Uncertain(""),
    var source: Configurable = Uncertain(""),
    var modrinth: Configurable = Uncertain(""),
    var curseforge: Configurable = Uncertain(""),
    val internal: MutableMap<String, String> = mutableMapOf()
) {
    val searchableName by lazy { name.value.spaceWords() }
    @Transient
    val processMessages = mutableListOf<String>()

    fun log(style: TextStyle, message: String) = synchronized(processMessages) {
        processMessages.add(style(message))
    }

    fun report() = buildString {
        appendLine("${name.value}:")
        appendLine("- Source: ${source.value}")
        appendLine("- Modrinth: ${modrinth.value}")
        appendLine("- Curseforge: ${curseforge.value}")

        if (processMessages.isNotEmpty())  {
            appendLine("- Process messages:")
            processMessages.forEach { appendLine("  - " + it.indentLines("    ").removePrefix("    ")) }
        }
    }.lineSequence().filter(String::isNotBlank).joinToString("\n")

    companion object {
        inline fun create(id: SearchID, block: SearchEntry.() -> Unit) = SearchEntry(id).apply(block)
    }
}
