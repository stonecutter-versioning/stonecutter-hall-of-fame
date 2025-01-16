package dev.kikugie.hall_of_fame

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass

interface ValueSerializable<T> {
    val value: T
}

open class ValueSerializer<T : ValueSerializable<*>>(private val cls: KClass<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Value", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): T = cls.constructors.first().call(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.value.toString())
}