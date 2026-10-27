// kotlinx's tree decoder names no key when a value is refused ("at path: $"), and neither does a type's own check;
// Rust's `toml` names the key and its line, which is what a user needs to fix the file. These decoders remember
// which key they are inside, so the error can say.
@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.tomlfile

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder

/** The keys from the root to the value being decoded, and where the first failure happened. */
internal class KeyPath {
    private val keys = ArrayList<String>()

    /** The keys of the value that failed, outermost first; a list index is written `[i]`. */
    var failed: List<String>? = null
        private set

    fun <T> at(
        key: String,
        body: () -> T,
    ): T {
        keys += key
        try {
            return body()
        } catch (e: IllegalArgumentException) {
            // The innermost frame sees the failure first; the frames it unwinds through must not overwrite it.
            if (failed == null) failed = keys.toList()
            throw e
        } finally {
            keys.removeAt(keys.lastIndex)
        }
    }
}

/** [inner], decoding through a decoder that keeps [path] current. */
internal class Tracked<T>(
    private val inner: DeserializationStrategy<T>,
    private val path: KeyPath,
) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = inner.descriptor

    override fun deserialize(decoder: Decoder): T = inner.deserialize(track(decoder, path))

    companion object {
        /**
         * A polymorphic serializer is left alone: JSON reads its discriminator only when it sees the serializer
         * itself, and a wrapper would hide it.
         */
        fun <T> of(
            inner: DeserializationStrategy<T>,
            path: KeyPath,
        ): DeserializationStrategy<T> = if (inner.descriptor.kind is PolymorphicKind) inner else Tracked(inner, path)
    }
}

private fun track(
    decoder: Decoder,
    path: KeyPath,
): Decoder = if (decoder is JsonDecoder) TrackingDecoder(decoder, path) else decoder

// A JsonDecoder, which kotlinx asks callers to opt in to implementing, because ketch's own serializers
// (ExtraPath, Installed) read the tree through one and would refuse a plain Decoder.
@OptIn(SealedSerializationApi::class)
private class TrackingDecoder(
    private val d: JsonDecoder,
    private val path: KeyPath,
) : JsonDecoder by d {
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = TrackingComposite(d.beginStructure(descriptor), path, descriptor.kind)

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = track(d.decodeInline(descriptor), path)

    // Delegation would hand `d`, not this decoder, to the serializer, and the path would stop here.
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = deserializer.deserialize(this)

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? = if (deserializer.descriptor.isNullable || decodeNotNullMark()) decodeSerializableValue(deserializer) else decodeNull()
}

private class TrackingComposite(
    private val c: CompositeDecoder,
    private val path: KeyPath,
    private val kind: SerialKind,
) : CompositeDecoder by c {
    /** A map's elements alternate key and value; the value is named by the key decoded just before it. */
    private var mapKey = ""

    private fun <T> at(
        descriptor: SerialDescriptor,
        index: Int,
        body: () -> T,
    ): T =
        when {
            kind == StructureKind.MAP && index % 2 == 0 -> body().also { mapKey = it.toString() }
            kind == StructureKind.MAP -> path.at(mapKey, body)
            kind == StructureKind.LIST -> path.at("[$index]", body)
            else -> path.at(descriptor.getElementName(index), body)
        }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T = at(descriptor, index) { c.decodeSerializableElement(descriptor, index, Tracked.of(deserializer, path), previousValue) }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = at(descriptor, index) { c.decodeNullableSerializableElement(descriptor, index, Tracked.of(deserializer, path), previousValue) }

    override fun decodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeBooleanElement(descriptor, index) }

    override fun decodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeByteElement(descriptor, index) }

    override fun decodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeShortElement(descriptor, index) }

    override fun decodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeIntElement(descriptor, index) }

    override fun decodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeLongElement(descriptor, index) }

    override fun decodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeFloatElement(descriptor, index) }

    override fun decodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeDoubleElement(descriptor, index) }

    override fun decodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeCharElement(descriptor, index) }

    override fun decodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) = at(descriptor, index) { c.decodeStringElement(descriptor, index) }
}

/** The dotted key of [keys], a list index joined to the key before it: `package[0].name`. */
internal fun dotted(keys: List<String>): String =
    keys.fold("") { acc, k ->
        when {
            acc.isEmpty() -> k
            k.startsWith('[') -> acc + k
            else -> "$acc.$k"
        }
    }
