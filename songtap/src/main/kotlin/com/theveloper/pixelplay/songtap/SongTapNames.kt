package com.theveloper.pixelplay.songtap

/**
 * The name a phone shows to other phones ("Song drop to Hoa's Pixel?", "From Hoa's Pixel").
 *
 * The listener sets it in Settings (default: the device's model name). It is clean text: control, zero width and
 * bidirectional characters stripped (the same [SongTapText] rules as a beamed title), spaces collapsed and trimmed,
 * at most [MAX_CHARS] characters (Unicode scalars, not UTF-16 units, not Swift `Character`s).
 *
 * How a name travels (the two platforms differ because an iPhone's Bluetooth advertisement can carry only its local name
 * and service UUIDs, while an Android phone can carry arbitrary bytes):
 *
 *  - In the advertisement ("short name"): an Android phone puts a manufacturer-specific block in its scan response with
 *    company id [SongTapWire.ADVERT_COMPANY_ID]: byte 0 = [SongTapWire.ADVERT_VERSION], then the name in UTF-8, cut at a
 *    scalar boundary to at most [ADVERT_NAME_MAX_BYTES] bytes ([advertName]). An iPhone advertises the same short name
 *    as its local name (iOS may cut it further). A phone reading an advertisement uses the Song Tap block when there is
 *    one and otherwise the local name, and runs it through [fromAdvert] either way.
 *  - Over a connection (the sender only, because only a sender runs a GATT server): the full name from the Name
 *    characteristic ([SongTapWire.encodeName]).
 *
 * A name is a label for the listener, never an identity: two phones can have the same name, and a stranger can claim any.
 */
object SongTapNames {

    const val MAX_CHARS = 20

    /** At most this many UTF-8 bytes of the name fit the advertisement's manufacturer block next to its 1 byte header. */
    const val ADVERT_NAME_MAX_BYTES = 24

    /** The most a clean name can be in UTF-8 (20 scalars of up to 4 bytes). */
    const val MAX_NAME_BYTES = MAX_CHARS * 4

    /** What a phone is called when it has no usable name. */
    const val FALLBACK = "Phone"

    /** [raw] as a clean name of at most [MAX_CHARS] scalars; empty when nothing usable is left. Idempotent. */
    fun clean(raw: String): String {
        val cps = SongTapText.sanitize(SongTapText.toCodePoints(raw), MAX_NAME_BYTES)
        val cut = if (cps.size > MAX_CHARS) cps.copyOf(MAX_CHARS) else cps
        // The cut can leave a trailing space; sanitize again drops it (and stays within the limits).
        return SongTapText.fromCodePoints(SongTapText.sanitize(cut, MAX_NAME_BYTES))
    }

    /** [clean], or [FALLBACK] when nothing is left. */
    fun orFallback(raw: String): String = clean(raw).ifEmpty { FALLBACK }

    /** True when [name] is already exactly what [clean] returns and is not empty. */
    fun isValid(name: String): Boolean = name.isNotEmpty() && clean(name) == name

    /** The short name for the advertisement: the clean name cut at a scalar boundary to [ADVERT_NAME_MAX_BYTES] bytes (never ends with a space). */
    fun advertName(name: String): String {
        val cps = SongTapText.toCodePoints(clean(name))
        return SongTapText.fromCodePoints(SongTapText.sanitize(cps, ADVERT_NAME_MAX_BYTES))
    }

    /** The name read out of an advertisement (the block's name bytes, or an iPhone's local name): clean, possibly shorter; null when nothing usable. */
    fun fromAdvert(raw: String): String? = clean(raw).ifEmpty { null }
}
