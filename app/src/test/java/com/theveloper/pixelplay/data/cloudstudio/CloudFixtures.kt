package com.theveloper.pixelplay.data.cloudstudio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The worker's golden examples (the `.json` files in `cloud/runpod-worker/schema/v1/examples/` of the PixlAudio-iOS
 * repo, copied byte for byte from iOS main @ 3ea323a into `src/test/resources/cloudstudio/worker/`) and the phone-side
 * fixtures the iOS tests use (`cloudstudio/phone/`: RunPod answers, a bucket listing, lyrics edge cases).
 */
internal object CloudFixtures {
    const val JOB_KEY = "6f1c2a9e-3b7d-4c11-9a0e-2d5f8b7c4e10"

    val workerInputs = listOf(
        "job.input.process.json", "job.input.transcribe.json", "job.input.volume.json", "job.input.bench.json",
        "job.input.selftest.json",
    )
    val workerResults = listOf("job.result.ok.json", "job.result.partial.json", "job.result.error.json", "job.result.poisoned.json")
    val workerLyrics = listOf("lyrics.aligned.json", "lyrics.transcribed.json")

    fun worker(name: String): String = read("cloudstudio/worker/$name")
    fun phone(name: String): String = read("cloudstudio/phone/$name")

    private fun read(path: String): String {
        val stream = requireNotNull(CloudFixtures::class.java.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** JSON with every null-valued key removed (the phone leaves nulls out; the worker writes some explicitly). */
    fun canonical(text: String): JsonElement = strip(CloudJson.parseToJsonElement(text))

    private fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(element.map(::strip))
        else -> element
    }
}
