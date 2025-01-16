package dev.kikugie.hall_of_fame.api

import com.github.ajalt.mordant.rendering.TextColors.*
import dev.kikugie.hall_of_fame.printStyled
import dev.kikugie.hall_of_fame.search.Excluded
import dev.kikugie.hall_of_fame.search.ProjectInfo
import dev.kikugie.hall_of_fame.search.SearchEntry
import dev.kikugie.hall_of_fame.search.SearchID
import dev.kikugie.hall_of_fame.similarTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.flatten
import kotlin.collections.map

object Modrinth {
    suspend fun get(entries: Collection<SearchEntry>): Map<SearchID, ProjectInfo> = coroutineScope {
        val grouped = entries.filter { it.modrinth !is Excluded && it.valid }.groupBy { it.slug != null }
        "Searching for projects on Modrinth... (%d excluded, %d known, %d unknown)"
            .format(entries.size - grouped.values.sumOf { it.size }, grouped[true]?.size ?: 0, grouped[false]?.size ?: 0)
            .let { printStyled(brightCyan, it) }

        val remaining = grouped.values.flatten().map { it.id }.let {
            ConcurrentHashMap.newKeySet<SearchID>(it.size).apply { addAll(it) }
        }
        buildMap {
            awaitAll(
                async { getKnown(grouped[true], remaining) },
                async { getUnknown(grouped[false], remaining) }
            ).forEach { this += it }
        }.also {
            printStyled(brightCyan, "Fetched ${it.size} matching projects from Modrinth.")
            if (remaining.isNotEmpty()) buildString {
                appendLine("Failed to fetch ${remaining.size} Modrinth projects:")
                remaining.forEach { appendLine(" - $it") }
            }.let { printStyled(brightRed, it) }
        }
    }

    private suspend fun getKnown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchID>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyMap()
        val mapped = entries.associateBy { it.slug!! }
        val url = "https://api.modrinth.com/v2/projects?ids=${mapped.keys.joinToString(",", "[", "]") { "\"$it\"" }}"
        async(Dispatchers.IO) { Client.get<List<ModrinthProject>>(url) }.await().getOrElse {
            printStyled(red, it.message ?: "Unknown ${it::class.simpleName}")
            return@coroutineScope emptyMap()
        }.filter {
            mapped[it.slug] != null
        }.associate {
            val entry = mapped[it.slug]!!
            remaining -= entry.id
            entry.log(green, "Fetched known Modrinth ${it.title} (${it.url})")
            entry.id to it.toInfo()
        }
    }

    private suspend fun getUnknown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchID>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyMap()
        val failed = ConcurrentHashMap<SearchID, MutableSet<String>>().apply {
            entries.associateTo(this) { it.id to mutableSetOf() }
        }
        var entries = entries.map {
            async(Dispatchers.IO) { it to search(it, it.name.value, failed) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.searchableName, failed) }
        }.awaitAll()
        entries.onEach { (entry, _) ->
            val messages = failed[entry.id]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return@onEach
            entry.log(yellow, "Modrinth search failed for '${entry.name.value}'. Hits:\n${messages.take(20).joinToString("\n")}")
        }.filter { (_, project) ->
            project != null
        }.associate { (entry, project) ->
            remaining -= entry.id
            entry.log(green, "Fetched unknown Modrinth ${project!!.title} (${project.url})")
            entry.id to project.toInfo()
        }
    }

    private suspend fun search(entry: SearchEntry, mod: String, collector: MutableMap<SearchID, MutableSet<String>>): ModrinthProject? {
        val url = "https://api.modrinth.com/v2/search?query=$mod&facets=[[\"project_type:mod\"]]"
        val result = Client.get<ModrinthSearch>(url).getOrElse {
            entry.log(red, "Modrinth search failed: ${it.stackTraceToString()}")
            return null
        }
        return result.hits.firstOrNull { mod similarTo it.slug || mod similarTo it.title } ?: run {
            collector.getOrPut(entry.id) { mutableSetOf() } += result.hits.map { "${it.title} (${it.url})" }
            null
        }
    }

    private val SearchEntry.slug get() = modrinth.takeIf { it.isKnown }?.value?.substringAfterLast('/')

    @Serializable
    private data class ModrinthSearch(val hits: List<ModrinthProject>)

    @Serializable
    private data class ModrinthProject(
        val slug: String,
        val title: String,
        val updated: String? = null,
        @SerialName("date_modified") val modified: String? = null,
        val description: String,
        val downloads: Int,
        @SerialName("icon_url") val iconUrl: String,
        @SerialName("source_url") val sourceUrl: String? = null,
    ) {
        val url get() = "https://modrinth.com/mod/$slug"

        fun toInfo() = ProjectInfo(
            title = title,
            description = description,
            icon = iconUrl,
            updated = Instant.parse(updated ?: modified!!),
            downloads = downloads,
            source = sourceUrl,
            modrinth = "https://modrinth.com/mod/$slug",
            curseforge = null
        )
    }
}