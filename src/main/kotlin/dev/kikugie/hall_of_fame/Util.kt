package dev.kikugie.hall_of_fame

import com.github.ajalt.mordant.rendering.TextStyle
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

infix fun String.similarTo(other: String) = similarity(other) >= 0.85

private fun String.raw() = lowercase()
    .replace("-", "")
    .replace("_", "")
    .replace(" ", "")

private fun String.similarity(other: String): Double {
    val r1 = raw()
    val r2 = other.raw()
    val distance = LevenshteinDistance().apply(r1, r2)
    return 1.0 - distance / max(r1.length, r2.length).toDouble()
}

inline fun <T> Iterable<T>.toArrayString(crossinline transform: (T) -> CharSequence = { it.toString() }) =
    joinToString(", ", "[", "]") { transform(it) }

val JsonElement.string get() = jsonPrimitive.content