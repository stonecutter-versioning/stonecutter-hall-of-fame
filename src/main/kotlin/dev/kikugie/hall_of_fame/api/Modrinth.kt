package dev.kikugie.hall_of_fame.api

import com.github.ajalt.mordant.rendering.TextColors.*
import dev.kikugie.hall_of_fame.printStyled
import dev.kikugie.hall_of_fame.search.Excluded
import dev.kikugie.hall_of_fame.search.ProjectInfo
import dev.kikugie.hall_of_fame.search.SearchEntry
import dev.kikugie.hall_of_fame.similarTo
import dev.kikugie.hall_of_fame.toArrayString
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
    suspend fun get(entries: Iterable<SearchEntry>): Map<SearchEntry, ProjectInfo> {
        val grouped = entries.filter { it.modrinth !is Excluded && it.valid }.groupBy { it.slug != null }
        printStyled(
            brightCyan, "Searching for projects on Modrinth... (%d excluded, %d known, %d unknown)"
                .format(entries.count { it.modrinth is Excluded }, grouped[true]?.size ?: 0, grouped[false]?.size ?: 0)
        )
        val remaining = ConcurrentHashMap.newKeySet<SearchEntry>().apply { addAll(grouped.values.flatten()) }
        return merge(getKnown(grouped[true], remaining), getUnknown(grouped[false], remaining)).toList().toMap().also {
            printStyled(brightCyan, "Fetched ${it.size} matching projects from Modrinth.")
            if (remaining.isNotEmpty())
                printStyled(brightRed, "Failed to fetch ${remaining.size} Modrinth projects: ${remaining.toArrayString { it.name.value }}")
        }
    }

    private suspend fun getKnown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchEntry>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyFlow()
        val mapped = entries.associateBy { it.slug!! }
        val url = "https://api.modrinth.com/v2/projects?ids${mapped.keys.joinToString(",", "[", "]") { "\"$it\"" }}"
        async(Dispatchers.IO) { Client.get<List<ModrinthProject>>(url) }.await().onFailure {
            printStyled(red, it.message ?: "Unknown ${it::class.simpleName}")
            return@coroutineScope emptyFlow()
        }.getOrThrow().asFlow().mapNotNull {
            mapped[it.slug]?.let { entry ->
                remaining -= entry
                entry.log(green, "Fetched known Modrinth ${it.title} (${it.url})")
                entry to it.toInfo()
            }
        }
    }

    private suspend fun getUnknown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchEntry>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyFlow()
        val failed = ConcurrentHashMap<SearchEntry, MutableSet<String>>().apply {
            entries.associateWithTo(this) { mutableSetOf() }
        }
        var entries = entries.map {
            async(Dispatchers.IO) { it to search(it, it.name.value, failed[it]!!) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.searchableName, failed[it]!!) }
        }.awaitAll()
        entries.asFlow().mapNotNull { (entry, project) ->
            failed[entry]?.takeIf { it.isNotEmpty() }?.let {
                entry.log(yellow, "Modrinth search failed for '${entry.name.value}'. Hits:\n${it.take(20).joinToString("\n")}")
            }

            project?.let {
                remaining -= entry
                entry.log(green, "Fetched unknown Modrinth ${it.title} (${it.url})")
                entry to it.toInfo()
            }
        }
    }

    private suspend fun search(entry: SearchEntry, mod: String, collector: MutableCollection<String>): ModrinthProject? {
        val url = "https://api.modrinth.com/v2/search?query=$mod&facets=[[\"project_type:mod\"]]"
        val result = Client.get<ModrinthSearch>(url).getOrElse {
            entry.log(red, "Modrinth search failed: ${it.stackTraceToString()}")
            return null
        }
        return result.hits.firstOrNull { mod similarTo it.slug || mod similarTo it.title } ?: run {
            collector.addAll(result.hits.map { "${it.title} (${it.url})" })
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
        val updated: String,
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
            updated = Instant.parse(updated),
            downloads = downloads,
            source = sourceUrl,
            modrinth = "https://modrinth.com/mod/$slug",
            curseforge = null
        )
    }
}