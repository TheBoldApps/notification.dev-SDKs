package dev.notification.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** An omitted sound uses the server default; an explicit null requests no sound. */
internal object IosNotificationOptionsSerializer : KSerializer<IosNotificationOptions> {
    override val descriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): IosNotificationOptions {
        val value = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return IosNotificationOptions(
            sound = value["sound"]?.jsonPrimitive?.contentOrNull,
            badge = value["badge"]?.jsonPrimitive?.intOrNull,
            interruptionLevel = value["interruptionLevel"]?.jsonPrimitive?.contentOrNull,
            mutableContent = value["mutableContent"]?.jsonPrimitive?.booleanOrNull,
            subtitle = value["subtitle"]?.jsonPrimitive?.contentOrNull,
            hasSound = "sound" in value
        )
    }

    override fun serialize(encoder: Encoder, value: IosNotificationOptions) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
            if (value.hasSound) put("sound", value.sound?.let(::JsonPrimitive) ?: JsonNull)
            value.badge?.let { put("badge", it) }
            value.interruptionLevel?.let { put("interruptionLevel", it) }
            value.mutableContent?.let { put("mutableContent", it) }
            value.subtitle?.let { put("subtitle", it) }
        })
    }
}
