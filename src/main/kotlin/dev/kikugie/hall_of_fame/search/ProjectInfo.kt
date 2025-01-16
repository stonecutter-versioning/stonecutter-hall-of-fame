package dev.kikugie.hall_of_fame.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ProjectInfo(
    val title: String,
    val description: String,
    val icon: String,
    val downloads: Int,
    val source: String?,
    val modrinth: String?,
    val curseforge: String?
) {
    @Transient
    val internal = mutableMapOf<String, Any>()
}
