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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

object CurseForge {
    // Whatcha lookin' at, it's public anyway
    private const val KEY = "$2a$10\$wuAJuNZuted3NORVmpgUC.m8sI.pv1tOPKZyBgLFGjxFp/br0lZCC"
    private const val BASE = "https://api.curseforge.com/v1/mods/search?gameId=432&%s=%s"

    suspend fun get(entries: Iterable<SearchEntry>): Map<SearchEntry, ProjectInfo> {
        val grouped = entries.filter { it.curseforge !is Excluded && it.valid }.groupBy { it.slug != null }
        printStyled(
            brightCyan, "Searching for projects on CurseForge... (%d excluded, %d known, %d unknown)"
                .format(entries.count { it.curseforge is Excluded }, grouped[true]?.size ?: 0, grouped[false]?.size ?: 0)
        )
        val remaining = ConcurrentHashMap.newKeySet<SearchEntry>().apply { addAll(grouped.values.flatten()) }
        return merge(getKnown(grouped[true], remaining), getUnknown(grouped[false], remaining)).toList().toMap().also {
            printStyled(brightCyan, "Fetched ${it.size} matching projects from CurseForge.")
            if (remaining.isNotEmpty())
                printStyled(brightRed, "Failed to fetch ${remaining.size} CurseForge projects: ${remaining.toArrayString { it.name.value }}")
        }
    }

    private suspend fun getKnown(entries: Iterable<SearchEntry>?, remaining: MutableCollection<SearchEntry>) = coroutineScope {
        if (entries == null) return@coroutineScope emptyFlow()
        val mapped = entries.associateBy { it.id!! }
        val url = "https://api.curseforge.com/v1/mods?{\"modIds\":$${mapped.keys.joinToString(",", "[", "]") { "\"$it\"" }}"
        async(Dispatchers.IO) { Client.get<CurseforgeSearch>(url).map { it.data } }.await().onFailure {
            printStyled(red, it.message ?: "Unknown ${it::class.simpleName}")
            return@coroutineScope emptyFlow()
        }.getOrThrow().asFlow().mapNotNull {
            mapped[it.id]?.let { entry ->
                remaining -= entry
                entry.log(green, "Fetched known CurseForge ${it.name} (${it.url})")
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
            val slug = it.slug
            if (slug == null) async { it to null }
            else async(Dispatchers.IO) { it to search(it, slug, "slug", failed[it]!!) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.name.value, "searchFilter", failed[it]!!) }
        }.awaitAll()
        entries = entries.map { (it, project) ->
            if (project != null) async { it to project }
            else async(Dispatchers.IO) { it to search(it, it.searchableName, "searchFilter", failed[it]!!) }
        }.awaitAll()
        entries.asFlow().mapNotNull { (entry, project) ->
            failed[entry]?.takeIf { it.isNotEmpty() }?.let {
                entry.log(yellow, "CurseForge search failed for '${entry.name.value}'. Hits:\n${it.take(20).joinToString("\n")}")
            }

            project?.let {
                remaining -= entry
                entry.log(green, "Fetched unknown CurseForge ${it.name} (${it.url})")
                entry to it.toInfo()
            }
        }
    }

    private suspend fun search(entry: SearchEntry, mod: String, type: String, collector: MutableCollection<String>): CurseforgeProject? {
        val url = "https://api.curseforge.com/v1/mods/search?gameId=432&$type=$mod"
        val result = Client.get<CurseforgeSearch>(url, mapOf("x-api-key" to KEY)).getOrElse {
            entry.log(red, "CurseForge search error: ${it.stackTraceToString()}")
            return null
        }
        return result.data.firstOrNull { (mod similarTo it.slug || mod similarTo it.name) && it.isValid } ?: run {
            collector.addAll(result.data.map { "${it.name} (${it.url})" })
            null
        }
    }

    private val SearchEntry.slug get() = curseforge.takeIf { it.isKnown }?.value?.substringAfterLast('/')
    private var SearchEntry.id: Int?
        get() = internal["curseforge_id"]?.toIntOrNull()
        set(value) {
            internal["curseforge_id"] = value!!.toString()
        }

    @Serializable private data class CurseforgeSearch(val data: List<CurseforgeProject>)
    @Serializable private data class CurseforgeProject(
        val id: Int,
        val slug: String,
        val name: String,
        val summary: String,
        val dateModified: String,
        val downloadCount: Int,
        val logo: JsonObject?,
        val links: JsonObject?,
    ) {
        val url get() = links?.get("websiteUrl")?.toString() ?: "https://www.curseforge.com/minecraft/mc-mods/$slug"
        val isValid get() = url.let { "mc-mods" in it || "bukkit-plugins" in it }

        fun toInfo() = ProjectInfo(
            title = name,
            description = summary,
            icon = (logo?.get("url") as? JsonPrimitive)?.content ?: "",
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