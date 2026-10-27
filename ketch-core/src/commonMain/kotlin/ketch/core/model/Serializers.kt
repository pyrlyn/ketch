package ketch.core.model

import ketch.core.text.BYTE_ORDER
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A path as the string Rust's `PathBuf` serializes to. */
internal object PathSerializer : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("ketch.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Path,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = Path(decoder.decodeString())
}

/** A string-keyed map written in key order, as Rust's `BTreeMap` is, so the files the two write match. */
internal object SortedStringMapSerializer : KSerializer<Map<String, String>> {
    private val map = MapSerializer(String.serializer(), String.serializer())
    override val descriptor = map.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<String, String>,
    ) = map.serialize(encoder, sorted(value))

    override fun deserialize(decoder: Decoder) = sorted(map.deserialize(decoder))

    private fun sorted(value: Map<String, String>) = value.entries.sortedWith(compareBy(BYTE_ORDER) { it.key }).associate { it.toPair() }
}
