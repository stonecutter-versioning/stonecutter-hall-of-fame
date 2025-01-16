package dev.kikugie.hall_of_fame.api

import com.github.ajalt.mordant.rendering.TextColors.*
import dev.kikugie.hall_of_fame.ValueSerializable
import dev.kikugie.hall_of_fame.ValueSerializer
import dev.kikugie.hall_of_fame.printStyled
import dev.kikugie.hall_of_fame.search.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTimedValue

object GitHub {
    private const val PAGE_SIZE = 100
    private const val URL = "https://api.github.com/search/code"

    suspend fun get(token: String, config: SearchConfig.RepositoryRequirements, entries: MutableCollection<SearchEntry>) {
        printStyled(brightCyan, "Searching for projects on GitHub...")
        val (files, time) = measureTimedValue {
            merge(queryStonecutter(token, "gradle"), queryStonecutter(token, "kts")).toList()
        }
        printStyled(brightCyan, "Found ${files.size} matching files in $time.")

        // Extract known entries with GitHub projects to prevent duplicates
        val mapped = entries.mapNotNull { e ->
            e.source
                .takeIf { "github.com" in it.value }
                ?.let { GitHubProject(it.value) to e }
        }.toMap()
        val valid = files.groupBy { it.project }.count { (project, files) ->
            val entry = mapped[project] ?: SearchEntry
                .create { name = uncertain(project.repo); source = verified(project.url) }
                .also { entries += it }
            entry.isValidRepository(config, project, files)
        }
        printStyled(brightCyan, "Found $valid valid projects.")
    }

    private fun SearchEntry.isValidRepository(
        config: SearchConfig.RepositoryRequirements,
        project: GitHubProject,
        files: List<GitHubEntry>
    ): Boolean {
        if (source is Excluded || !valid) return false
        val repositoryValid = config.allowProject(project).also {
            if (!it) log(red, "Has invalid repository: ${project.value}")
        }

        var anyFileValid = false
        for (f in files) anyFileValid = config.allowFile(f.file).also {
            if (!it) log(red, "Has invalid file: ${f.file}")
        } || anyFileValid
        return (repositoryValid && anyFileValid).also { valid = it }
    }

    private fun queryStonecutter(token: String, extension: String) = flow {
        val parameters = Client.parameters {
            headers["Accept"] = "application/vnd.github+json"
            headers["X-GitHub-Api-Version"] = "2022-11-28"
            headers["Authorization"] = "Bearer $token"
        }
        var current = 1
        do {
            val url = "$URL?per_page=$PAGE_SIZE&page=$current&q=filename:stonecutter extension:$extension"
            val response = Client.get<GitHubRootResponse>(url, parameters)
                .recover {
                    if (it.message?.contains("API rate limit") != true) throw it
                    else {
                        printStyled(brightYellow, "GitHub rate limit exceeded, resuming in 1 minute...")
                        delay(1.minutes)
                    }
                    null
                }.getOrThrow()
            if (response == null) continue
            response.items.asFlow().map { GitHubEntry(it.file) }.let { emitAll(it) }
            current++
        } while (response == null || response.total / PAGE_SIZE >= current - 1)
    }

    @Serializable
    private data class GitHubRootResponse(
        @SerialName("total_count") val total: Int,
        @SerialName("incomplete_results") val incomplete: Boolean,
        val items: List<GitHubFile>
    )

    @Serializable
    private data class GitHubFile(
        @SerialName("html_url") val file: String
    )

    @Serializable(with = GitHubProjectSerializer::class)
    @JvmInline
    value class GitHubProject(override val value: String) : ValueSerializable<String> {
        val owner: String get() = value.substringBefore('/')
        val repo: String get() = value.substringAfter('/')
        val url: String get() = "https://github.com/$value"
        override fun toString(): String = value.toString()
    }

    @Serializable(with = GitHubEntrySerializer::class)
    @JvmInline
    value class GitHubEntry(override val value: String) : ValueSerializable<String> {
        val file
            get() = value
                .substringAfter("blob/")
                .substringAfter('/')
        val project
            get() = value
                .substringBefore("/blob")
                .substringAfter("https://github.com/")
                .let(::GitHubProject)

        override fun toString(): String = value.toString()
    }

    private object GitHubProjectSerializer : ValueSerializer<GitHubProject>(GitHubProject::class)
    private object GitHubEntrySerializer : ValueSerializer<GitHubEntry>(GitHubEntry::class)
}