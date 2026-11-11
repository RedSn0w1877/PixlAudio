package com.theveloper.pixelplay.data.spotiflac

/**
 * What the first bytes of an audio file say about it. Monochrome sends everything as
 * `application/octet-stream`, and its catalogue mixes 16-bit/44.1 kHz FLAC, 24-bit FLAC up to 192 kHz
 * and the odd AAC-in-MP4 file, so the format and rate come from the file itself. Same rules as iOS
 * (`SpotiFLACParsing.audioFileFacts`).
 */
data class AudioFileFacts(
    val format: Format,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null
) {
    enum class Format(val mimeType: String?) {
        FLAC("audio/flac"),
        MP4("audio/mp4"),
        MP3("audio/mpeg"),
        AAC("audio/aac"),
        OGG("audio/ogg"),
        WEBM("audio/webm"),
        UNKNOWN(null)
    }

    /** The FLAC sample rate, or null for anything that isn't FLAC (or a rate the header didn't give). */
    val flacSampleRateHz: Int? get() = sampleRateHz.takeIf { format == Format.FLAC }

    companion object {
        /** Enough for FLAC's STREAMINFO (bytes 8-41) and every other signature here. */
        const val HEAD_BYTES = 64

        fun parse(head: ByteArray): AudioFileFacts {
            fun u(i: Int): Int = if (i < head.size) head[i].toInt() and 0xFF else -1
            fun ascii(at: Int, text: String): Boolean =
                head.size >= at + text.length && text.indices.all { u(at + it) == text[it].code }

            return when {
                ascii(0, "fLaC") -> flac(head)
                ascii(4, "ftyp") -> AudioFileFacts(Format.MP4)
                ascii(0, "ID3") -> AudioFileFacts(Format.MP3)
                ascii(0, "OggS") -> AudioFileFacts(Format.OGG)
                u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> AudioFileFacts(Format.WEBM)
                u(0) == 0xFF && (u(1) and 0xF6) == 0xF0 -> AudioFileFacts(Format.AAC) // ADTS
                u(0) == 0xFF && u(1) >= 0 && (u(1) and 0xE0) == 0xE0 -> AudioFileFacts(Format.MP3) // frame sync
                else -> AudioFileFacts(Format.UNKNOWN)
            }
        }

        /**
         * `fLaC`, then the STREAMINFO block (always first): its header at 4-7, then min/max block size
         * (4 bytes), min/max frame size (6), then 20 bits of sample rate, 3 of channels - 1 and 5 of
         * bits per sample - 1, starting at byte 18.
         */
        private fun flac(head: ByteArray): AudioFileFacts {
            if (head.size < 22 || (head[4].toInt() and 0x7F) != 0) return AudioFileFacts(Format.FLAC)
            fun u(i: Int) = head[i].toInt() and 0xFF
            val sampleRate = (u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4)
            val channels = ((u(20) shr 1) and 0x07) + 1
            val bitDepth = (((u(20) and 0x01) shl 4) or (u(21) shr 4)) + 1
            return AudioFileFacts(
                format = Format.FLAC,
                sampleRateHz = sampleRate.takeIf { it in 1..655_350 },
                bitDepth = bitDepth,
                channels = channels
            )
        }
    }
}
