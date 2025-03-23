package dev.kikugie.hall_of_fame

import com.github.ajalt.mordant.rendering.TextStyle
import dev.kikugie.hall_of_fame.search.SearchEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.text.similarity.LevenshteinDistance
import kotlin.math.max

operator fun Char.times(n: Int) = require(n >= 0) { "n must be non-negative, was $n" } then
        if (n == 0) "" else String(CharArray(n) { this })

@Suppress("NOTHING_TO_INLINE")
inline infix fun <T> Any?.then(next: T) = next


fun printStyled(style: TextStyle, message: Any) = println(style(message.toString()))
fun printStyled(vararg styles: TextStyle, message: Any) {
    var result = message
    styles.forEach { result = it(result.toString()) }
    println(result)
}
fun printStyled(style: TextStyle, indent: Int, message: Any) = printStyled(style, ' ' * indent + message)
fun printStyled(vararg styles: TextStyle, indent: Int, message: Any) = printStyled(*styles, message = ' ' * indent + message)

fun String.indentLines(indent: String) = lineSequence().joinToString("\n") { indent + it }

fun String.spaceWords() = this
    .replace("-", " ")
    .replace("_", " ")
    .spaceCapitals()
    .trim()

private fun String.spaceCapitals() = mapIndexed { index, char ->
    if (char.isUpperCase()) when {
        getOrNull(index + 1)?.isLowerCase() == true -> " $char"
        getOrNull(index - 1)?.isLowerCase() == true -> " $char"
        else -> char.toString()
    } else char.toString()
}.joinToString("")

private fun String.raw() = lowercase()
    .replace("-", "")
    .replace("_", "")
    .replace(" ", "")

infix fun String.sim(other: String): Int =
    LevenshteinDistance.getDefaultInstance().apply(this.raw(), other.raw())

inline fun <T> Iterable<T>.toArrayString(crossinline transform: (T) -> CharSequence = { it.toString() }) =
    joinToString(", ", "[", "]") { transform(it) }

val JsonElement.string get() = jsonPrimitive.content
fun Iterable<SearchEntry>.associate() = associateBy { it.id }