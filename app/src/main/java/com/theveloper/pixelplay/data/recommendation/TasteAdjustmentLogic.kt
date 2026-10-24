package com.theveloper.pixelplay.data.recommendation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the tool asks for. `unmute` / `unblock` take a mute / block back. */
enum class TasteAction(val key: String) {
    BOOST("boost"), REDUCE("reduce"), MUTE("mute"), UNMUTE("unmute"), BLOCK("block"), UNBLOCK("unblock");

    companion object {
        fun fromKey(key: String?): TasteAction? = entries.firstOrNull { it.key == key }
    }
}

/** One requested change, its target already resolved ([TasteTargetResolver]). */
data class TasteRequest(
    val action: TasteAction,
    val target: TasteTarget,
    val key: String,
    val altKey: String?,
    val label: String,
    val strength: Int,
    val reason: String,
    val expiresDays: Int?,
)

/** What one request did, in words the assistant can repeat to the listener. */
data class TasteChange(val adjustmentId: String?, val text: String, val changed: Boolean)

data class TasteOutcome(val adjustments: List<TasteAdjustment>, val changes: List<TasteChange>) {
    val changed: Boolean get() = changes.any { it.changed }
}

/**
 * Applies requests to the stored list. Pure (TasteAdjustmentsTest): the repository only persists the result.
 *
 * - A boost replaces a reduce on the same target and the other way round; one of each never stacks.
 * - A block replaces a mute on the same target (it covers it); muting something blocked changes nothing.
 * - `unmute` / `unblock` remove exactly that kind; undoing a boost or reduce goes through its id.
 */
object TasteAdjustmentLogic {
    /** Active adjustments beyond this are refused (the listener can always remove some). */
    const val MAX_ACTIVE = 300
    const val MAX_REASON_CHARS = 200
    const val MAX_LABEL_CHARS = 120
    const val MAX_EXPIRES_DAYS = 3_650
    private const val DAY_MS = 86_400_000L

    fun apply(
        current: List<TasteAdjustment>,
        requests: List<TasteRequest>,
        nowMs: Long,
        setBy: TasteSetBy,
        newId: () -> String,
    ): TasteOutcome {
        val list = current.filter { it.isActive(nowMs) }.toMutableList()
        val changes = ArrayList<TasteChange>(requests.size)

        fun add(request: TasteRequest, kind: TasteKind, text: String) {
            val adjustment = TasteAdjustment(
                id = newId(),
                kind = kind,
                target = request.target,
                key = request.key,
                altKey = request.altKey,
                label = request.label.take(MAX_LABEL_CHARS),
                strength = TasteAdjustmentMath.strengthOf(request.strength),
                reason = request.reason.trim().take(MAX_REASON_CHARS),
                setBy = setBy,
                createdAtMs = nowMs,
                expiresAtMs = request.expiresDays?.let { nowMs + it.coerceIn(1, MAX_EXPIRES_DAYS) * DAY_MS },
            )
            list += adjustment
            changes += TasteChange(adjustment.id, text, true)
        }

        loop@ for (request in requests) {
            fun same(other: TasteAdjustment) = other.target == request.target && other.key == request.key
            val what = "${request.label} (${request.target.key})"
            if (request.action in ADDING && list.size >= MAX_ACTIVE) {
                changes += TasteChange(null, "Not applied to $what: there are already $MAX_ACTIVE active changes. Remove some first.", false)
                continue@loop
            }
            when (request.action) {
                TasteAction.BOOST, TasteAction.REDUCE -> {
                    val kind = if (request.action == TasteAction.BOOST) TasteKind.BOOST else TasteKind.REDUCE
                    list.removeAll { same(it) && (it.kind == TasteKind.BOOST || it.kind == TasteKind.REDUCE) }
                    val strength = TasteAdjustmentMath.strengthOf(request.strength)
                    add(request, kind, "${if (kind == TasteKind.BOOST) "Boosted" else "Reduced"} $what: ${TasteAdjustmentMath.describe(kind, strength)}")
                }
                TasteAction.MUTE -> {
                    if (list.any { same(it) && it.kind == TasteKind.BLOCK }) {
                        changes += TasteChange(null, "$what is already blocked, which covers muting it.", false)
                        continue@loop
                    }
                    list.removeAll { same(it) && it.kind == TasteKind.MUTE }
                    add(request, TasteKind.MUTE, "Muted $what: out of mixes and shelves, still playable by hand")
                }
                TasteAction.BLOCK -> {
                    list.removeAll { same(it) && (it.kind == TasteKind.MUTE || it.kind == TasteKind.BLOCK) }
                    add(request, TasteKind.BLOCK, "Blocked $what: never recommended or auto-queued")
                }
                TasteAction.UNMUTE -> {
                    val removed = list.removeAll { same(it) && it.kind == TasteKind.MUTE }
                    changes += TasteChange(null, if (removed) "Unmuted $what" else "$what wasn't muted", removed)
                }
                TasteAction.UNBLOCK -> {
                    val removed = list.removeAll { same(it) && it.kind == TasteKind.BLOCK }
                    changes += TasteChange(null, if (removed) "Unblocked $what" else "$what wasn't blocked", removed)
                }
            }
        }
        return TasteOutcome(list, changes)
    }

    private val ADDING = setOf(TasteAction.BOOST, TasteAction.REDUCE, TasteAction.MUTE, TasteAction.BLOCK)
}

/** The stored form: a JSON array, written by hand (no reflection, so R8 can't break it). */
object TasteAdjustmentCodec {
    fun encode(list: List<TasteAdjustment>): String = JsonArray(list.map { toJson(it) }).toString()

    fun decode(raw: String?): List<TasteAdjustment> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element -> (element as? JsonObject)?.let { fromJson(it) } }
    }

    private fun toJson(a: TasteAdjustment): JsonObject = buildJsonObject {
        put("id", a.id)
        put("kind", a.kind.key)
        put("target", a.target.key)
        put("key", a.key)
        a.altKey?.let { put("alt", it) }
        put("label", a.label)
        put("strength", a.strength)
        put("reason", a.reason)
        put("by", a.setBy.key)
        put("at", a.createdAtMs)
        a.expiresAtMs?.let { put("until", it) }
    }

    private fun fromJson(o: JsonObject): TasteAdjustment? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val kind = TasteKind.fromKey(o.str("kind")) ?: return null
        val target = TasteTarget.fromKey(o.str("target")) ?: return null
        val key = o.str("key")?.takeIf { it.isNotBlank() } ?: return null
        return TasteAdjustment(
            id = id,
            kind = kind,
            target = target,
            key = key,
            altKey = o.str("alt")?.takeIf { it.isNotBlank() },
            label = o.str("label")?.takeIf { it.isNotBlank() } ?: key,
            strength = TasteAdjustmentMath.strengthOf((o["strength"] as? JsonPrimitive)?.intOrNull),
            reason = o.str("reason").orEmpty(),
            setBy = TasteSetBy.fromKey(o.str("by")),
            createdAtMs = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            expiresAtMs = (o["until"] as? JsonPrimitive)?.longOrNull,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
