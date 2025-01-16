package dev.kikugie.hall_of_fame.search

import dev.kikugie.hall_of_fame.api.GitHub.GitHubProject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchConfig(
    val repositories: RepositoryRequirements = RepositoryRequirements()
) {
    @Serializable
    data class RepositoryRequirements(
        @SerialName("excluded_owners") val excludedOwners: Set<String> = emptySet(),
        @SerialName("excluded_names") val excludedNames: Set<String> = emptySet(),
        @SerialName("excluded_repos") val excludedRepos: Set<String> = emptySet(),
        @SerialName("required_files") val requiredFiles: Set<String> = emptySet(),
        @SerialName("disallowed_words") val disallowedWords: List<String> = emptyList(),
    ) {
        fun allowProject(project: GitHubProject) = project.value !in excludedRepos
                && project.owner !in excludedOwners
                && project.repo !in excludedNames
                && disallowedWords.none { it in project.repo.lowercase() }

        fun allowFile(file: String) = "src/" !in file && file.substringAfterLast('/') in requiredFiles
    }
}