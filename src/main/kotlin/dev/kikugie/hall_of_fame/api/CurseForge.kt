package dev.kikugie.hall_of_fame.api

import com.github.ajalt.mordant.rendering.TextColors.*
import dev.kikugie.hall_of_fame.printStyled
import dev.kikugie.hall_of_fame.search.Excluded
import dev.kikugie.hall_of_fame.search.ProjectInfo
import dev.kikugie.hall_of_fame.search.SearchEntry
import dev.kikugie.hall_of_fame.search.SearchID
import dev.kikugie.hall_of_fame.similarTo
import dev.kikugie.hall_of_fame.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

object CurseForge {
    // Whatcha lookin' at, it's public anyway
    private const val KEY = "$2a$10\$wuAJuNZuted3NORVmpgUC.m8sI.pv1tOPKZyBgLFGjxFp/br0lZCC"

    suspend fun get(entries: Collection<SearchEntry>): Map<SearchID, ProjectInfo> = coroutineScope {
        val grouped = entries.filter { it.curseforge !is Excluded && it.valid }.groupBy { it.cf != null }
        "Searching for projects on CurseForge... (%d excluded, %d known, %d unknown)"
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
            printStyled(brightCyan, "Fetched ${it.size} matching projects from CurseForge.")
            if (remaining.isNotEmpty()) buildString {
                appendLine("Failed to fetch ${remaining.size} CurseForge projects:")
                remaining.forEach { appendLine(" - $it") }
            }.let { printStyled(brightRed, it) }
        }
    }

    private suspend fun getKnown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchID>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyMap()
        val mapped = entries.associateBy { it.cf!! }
        val url = "https://api.curseforge.com/v1/mods"
        val json = "{\"modIds\":${mapped.keys.joinToString(",", "[", "]") { "\"$it\"" }}}"
        val parameters = Client.parameters {
            method = "POST"
            headers["x-api-key"] = KEY
            body = json.toRequestBody("application/json".toMediaType())
        }
        async(Dispatchers.IO) { Client.get<CurseforgeSearch>(url, parameters).map { it.data } }.await().getOrElse {
            printStyled(red, it.message ?: "Unknown ${it::class.simpleName}")
            return@coroutineScope emptyMap()
        }.filter {
            it.id in mapped
        }.associate {
            val entry = mapped[it.id]!!
            remaining -= entry.id
            entry.log(green, "Fetched known CurseForge ${it.name} (${it.url})")
            entry.id to it.toInfo()
        }
    }

    private suspend fun getUnknown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchID>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyMap()
        val failed = ConcurrentHashMap<SearchID, MutableSet<String>>().apply {
            entries.associateTo(this) { it.id to mutableSetOf() }
        }
        var entries = entries.map {
            val slug = it.slug
            if (slug == null) async { it to null }
            else async(Dispatchers.IO) { it to search(it, slug, "slug", failed) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.name.value, "searchFilter", failed) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.searchableName, "searchFilter", failed) }
        }.awaitAll()
        entries.onEach { (entry, _) ->
            val messages = failed[entry.id]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return@onEach
            entry.log(yellow, "CurseForge search failed for '${entry.name.value}'. Hits:\n${messages.take(20).joinToString("\n")}")
        }.filter { (_, project) ->
            project != null
        }.associate { (entry, project) ->
            remaining -= entry.id
            entry.log(green, "Fetched unknown CurseForge ${project!!.name} (${project.url})")
            entry.id to project.toInfo()
        }
    }

    private suspend fun search(
        entry: SearchEntry,
        mod: String,
        type: String,
        collector: MutableMap<SearchID, MutableSet<String>>
    ): CurseforgeProject? {
        val url = "https://api.curseforge.com/v1/mods/search?gameId=432&sortOrder=desc&$type=$mod"
        val parameters = Client.parameters { headers["x-api-key"] = KEY }
        val result = Client.get<CurseforgeSearch>(url, parameters).getOrElse {
            entry.log(red, "CurseForge search error: ${it.stackTraceToString()}")
            return null
        }
        return result.data.firstOrNull { (mod similarTo it.slug || mod similarTo it.name) && it.isValid } ?: run {
            collector.getOrPut(entry.id) { mutableSetOf() } += result.data.map { "${it.name} (${it.url})" }
            null
        }
    }

    private val SearchEntry.slug get() = curseforge.takeIf { it.isKnown }?.value?.substringAfterLast('/')
    private var SearchEntry.cf: Int?
        get() = internal["curseforge_id"]?.toIntOrNull()
        set(value) {
            internal["curseforge_id"] = value!!.toString()
        }

    @Serializable
    private data class CurseforgeSearch(val data: List<CurseforgeProject>)

    @Serializable
    private data class CurseforgeProject(
        val id: Int,
        val slug: String,
        val name: String,
        val summary: String,
        val dateModified: String,
        val downloadCount: Int,
        val logo: JsonObject?,
        val links: JsonObject?,
    ) {
        val url get() = links?.get("websiteUrl")?.string ?: "https://www.curseforge.com/minecraft/mc-mods/$slug"
        val isValid get() = url.let { "mc-mods" in it || "bukkit-plugins" in it }

        fun toInfo() = ProjectInfo(
            title = name,
            description = summary,
            icon = (logo?.get("url") as? JsonPrimitive)?.string ?: "",
            updated = Instant.parse(dateModified),
            downloads = downloadCount,
            source = links?.get("sourceUrl").toString(),
            modrinth = null,
            curseforge = url
        ).apply {
            internal["curseforge_id"] = id
        }
    }
}