package dev.kikugie.hall_of_fame.search

import dev.kikugie.hall_of_fame.api.CurseForge
import dev.kikugie.hall_of_fame.api.GitHub
import dev.kikugie.hall_of_fame.api.Modrinth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object Collector {
    suspend fun get(githubToken: String, config: SearchConfig, entries: List<SearchEntry>) = coroutineScope {
        val mutable = entries.toMutableList()
        GitHub.get(githubToken, config.repositories, mutable)
        val (mr, cf) = listOf(async { Modrinth.get(mutable) }, async { CurseForge.get(mutable) }).awaitAll()
        mutable.toList() to mutable.mapNotNull {
            var mrInfo = mr[it]
            var cfInfo = cf[it]
            if (mrInfo != null) it.patch(mrInfo)
            if (cfInfo != null) it.patch(cfInfo)
            if (mrInfo != null && cfInfo != null) mrInfo.merge(cfInfo).apply(it)
            else mrInfo?.apply(it) ?: cfInfo?.apply(it)
        }.also {
            mutable.forEach { println(it.report() + "\n") }
        }
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
        downloads = downloads + other.downloads,
        source = source ?: other.source,
        modrinth = modrinth ?: other.modrinth,
        curseforge = curseforge ?: other.curseforge,
    )

    private fun ProjectInfo.apply(entry: SearchEntry) = ProjectInfo(
        title = if (entry.name is Overridden) entry.name.value else title,
        description = description,
        icon = icon,
        downloads = downloads,
        source = if (entry.source is Overridden) entry.source.value else source,
        modrinth = if (entry.modrinth is Overridden) entry.modrinth.value else modrinth,
        curseforge = if (entry.curseforge is Overridden) entry.curseforge.value else curseforge,
    )
}