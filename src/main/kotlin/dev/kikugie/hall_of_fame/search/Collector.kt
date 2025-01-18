package dev.kikugie.hall_of_fame.search

import com.github.ajalt.mordant.rendering.TextColors
import dev.kikugie.hall_of_fame.api.CurseForge
import dev.kikugie.hall_of_fame.api.GitHub
import dev.kikugie.hall_of_fame.api.Modrinth
import dev.kikugie.hall_of_fame.associate
import dev.kikugie.hall_of_fame.printStyled
import dev.kikugie.hall_of_fame.then
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object Collector {
    suspend fun get(githubToken: String?, config: SearchConfig, cache: List<SearchEntry>) = coroutineScope {
        val searches = cache.associate().toMutableMap().patch(config.entries).let {
            if (githubToken != null) GitHub.get(githubToken, config.repositories, it).values
            else it.values.onEach {
                if (!it.name.isKnown) it.name = uncertain(it.id.substringAfter('/'))
            }
        }

        val (mr, cf) = awaitAll(
            async { Modrinth.get(searches) },
            async { CurseForge.get(searches) }
        )
        val projects = searches.mapNotNull { it -> it.patch(mr[it.id], cf[it.id]) }.toMap().also {
            searches.forEach { println(it.report() + "\n") }
        }
        printStyled(TextColors.brightCyan, "Fetched ${projects.size} matching projects from all APIs.")
        searches to projects
    }

    private fun MutableMap<SearchID, SearchEntry>.patch(overrides: List<SearchEntry>) = apply {
        overrides.associate().forEach { id, entry -> this[id]?.patch(entry) ?: put(id, entry) }
    }

    private fun SearchEntry.patch(modrinth: ProjectInfo?, curseforge: ProjectInfo?) = when {
        modrinth != null && curseforge != null -> modrinth.merge(curseforge)
        modrinth != null -> modrinth
        curseforge != null -> curseforge
        else -> null
    }?.apply(this)?.let { patch(it) then id to it }

    private fun SearchEntry.patch(override: SearchEntry) {
        if (!override.valid) valid = false then return
        if (override.name.isKnown) name = override.name
        if (override.source.isKnown) source = override.source
        if (override.modrinth.isKnown) modrinth = override.modrinth
        if (override.curseforge.isKnown) curseforge = override.curseforge
    }

    private fun SearchEntry.patch(info: ProjectInfo) {
        if (!name.isKnown) name = verified(info.title)
        if (!source.isKnown && info.source != null) source = verified(info.source)
        if (!modrinth.isKnown && info.modrinth != null) modrinth = verified(info.modrinth)
        if (!curseforge.isKnown && info.curseforge != null) curseforge = verified(info.curseforge)
        internal.putAll(info.internal.mapValues { (_, it) -> it.toString() })
    }

    private fun ProjectInfo.merge(other: ProjectInfo) = ProjectInfo(
        title = title,
        description = description,
        icon = icon,
        updated = if (updated != null && other.updated != null) minOf(updated, other.updated) else updated ?: other.updated,
        downloads = downloads + other.downloads,
        source = source ?: other.source,
        modrinth = modrinth ?: other.modrinth,
        curseforge = curseforge ?: other.curseforge,
    ).apply {
        internal.putAll(this@merge.internal)
        internal.putAll(other.internal)
    }

    private fun ProjectInfo.apply(entry: SearchEntry) = ProjectInfo(
        title = if (entry.name.isKnown) entry.name.value else title,
        description = description,
        icon = icon,
        updated = updated,
        downloads = downloads,
        source = if (entry.source.let { it.isKnown && it !is Excluded }) entry.source.value else source,
        modrinth = if (entry.modrinth.let { it.isKnown && it !is Excluded }) entry.modrinth.value else modrinth,
        curseforge = if (entry.curseforge.let { it.isKnown && it !is Excluded }) entry.curseforge.value else curseforge,
    ).also {
        it.internal.putAll(this@apply.internal)
        entry.internal["downloads"] = it.downloads.toString()
        entry.internal["updated"] = it.updated.toString()
        entry.internal["icon"] = it.icon
    }
}