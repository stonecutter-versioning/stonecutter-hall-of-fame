package dev.kikugie.hall_of_fame.search

import dev.kikugie.hall_of_fame.ValueSerializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

fun uncertain(value: String) = Uncertain(value)
fun verified(value: String) = Verified(value)

@Serializable(with = ConfigurableSerializer::class)
sealed interface Configurable : ValueSerializable<String> {
    val prefixed: String

    val isKnown: Boolean get() = this is Verified || this is Overridden
    val isPresent: Boolean get() = this !is Excluded && value.isNotBlank()
}

@Serializable
data object Excluded : Configurable {
    override val value: String = "%EXCLUDED%"
    override val prefixed: String get() = value
    override fun toString(): String = "Excluded()"
}

@Serializable
@JvmInline
value class Overridden(override val value: String) : Configurable {
    override val prefixed: String get() = "${PREFIX}$value"
    override fun toString(): String = "Overridden($value)"

    companion object {
        const val PREFIX = ""
    }
}

@Serializable
@JvmInline
value class Uncertain(override val value: String) : Configurable {
    override val prefixed: String get() = "$PREFIX$value"
    override fun toString(): String = "Uncertain($value)"

    companion object {
        const val PREFIX = "?"
    }
}

@Serializable
@JvmInline
value class Verified(override val value: String) : Configurable {
    override val prefixed: String get() = "${PREFIX}$value"
    override fun toString(): String = "Verified($value)"

    companion object {
        const val PREFIX = "!"
    }
}

object ConfigurableSerializer : KSerializer<Configurable> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Configurable", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, it: Configurable) = encoder.encodeString(it.prefixed)
    override fun deserialize(decoder: Decoder): Configurable = when (val it = decoder.decodeString()) {
        "%EXCLUDED%" -> Excluded
        else -> when {
            it.startsWith(Verified.PREFIX) -> Verified(it.drop(1))
            it.startsWith(Uncertain.PREFIX) -> Uncertain(it.drop(1))
            else -> Overridden(it)
        }
    }
}