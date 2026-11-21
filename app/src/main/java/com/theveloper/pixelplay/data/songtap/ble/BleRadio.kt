package com.theveloper.pixelplay.data.songtap.ble

import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapSensitivity

/** Whether the phone's Bluetooth can be used for Song Tap right now, and if not why. */
enum class BleStatus {
    /** Not started (Song Tap is not armed on screen). */
    IDLE,

    /** Advertising and scanning. */
    ACTIVE,

    /** This phone has no Bluetooth LE, or cannot advertise. */
    UNSUPPORTED,

    /** The Bluetooth permission has not been granted (it is asked for when Song Tap is turned on). */
    NO_PERMISSION,

    /** Bluetooth is switched off. */
    BLUETOOTH_OFF,
}

/**
 * What the radio tells [BleTransport]. Every call comes from a Bluetooth thread, so the transport takes its own
 * lock; nothing here blocks.
 *
 * `address` is the one the scan or the connection reported. A phone's address changes every few minutes (random,
 * private), which is what makes a peer unlinkable from one session to the next, and why nothing here treats it as
 * an identity.
 */
interface BleRadioListener {
    /**
     * A scan result: a phone advertising [role]'s service, with its signal strength in dBm and the short name it advertised
     * ([name], already clean, null when it advertised none).
     */
    fun onSeen(address: String, role: Role, rssi: Int, name: String?)

    /** Our connection to a sender's server is ready (MTU set, service found, notifications on), or failed. */
    fun onConnected(address: String, ok: Boolean)
    fun onDisconnected(address: String)

    /** The answer to [BleRadio.readInfo]; null when the read failed. */
    fun onInfoRead(address: String, value: ByteArray?)

    /** The answer to [BleRadio.readName]; null when the read failed. */
    fun onNameRead(address: String, value: ByteArray?)

    /** The answer to [BleRadio.readOffer]; null when the read failed or the sender had nothing to give yet. */
    fun onOfferRead(address: String, value: ByteArray?)

    /** The answer to [BleRadio.writeControl], in the order the writes were asked for. */
    fun onControlWritten(address: String, ok: Boolean)

    /** The sender notified us (the Event characteristic). */
    fun onNotified(address: String, value: ByteArray)

    fun onStatus(status: BleStatus)

    // ── The sender's own GATT server ──────────────────────────────────────────────────

    /** A client read Info. */
    fun serverInfo(): ByteArray

    /** A client read Name: the value of `SongTapWire.encodeName` for this phone's name. */
    fun serverName(): ByteArray

    /** A client read Offer. Null answers with the "not ready" error. */
    fun serverOffer(client: String): ByteArray?

    /** A client wrote Control. */
    fun serverControl(client: String, value: ByteArray): Boolean
    fun serverClientGone(client: String)
}

/**
 * The thin layer over the platform's Bluetooth LE, so everything that decides anything lives in [BleTransport]
 * and can be tested without a radio. Calls return at once; results come back through [BleRadioListener].
 */
interface BleRadio {
    /**
     * Starts advertising [role]'s service (the sender also opens its GATT server) with [name] as the short name (spec 5, at
     * the lowest transmit power the platform allows) and scanning for both services.
     * Returns [BleStatus.ACTIVE], or the reason it could not start; then nothing is running.
     */
    fun start(role: Role, listener: BleRadioListener, name: String): BleStatus

    /** Stops advertising, scanning, the server and every connection. */
    fun stop()

    /** Client: connect to the sender seen at [address], ask for the big MTU, find the service and subscribe to Event. */
    fun connect(address: String)
    fun disconnect(address: String)
    fun readInfo(address: String)
    fun readName(address: String)
    fun readOffer(address: String)
    fun writeControl(address: String, value: ByteArray)

    /** Server: send [value] as a notification of the Event characteristic to the connected [client]. */
    fun notify(client: String, value: ByteArray)

    /** Server: drop a client. */
    fun dropClient(client: String)
}

/** The two clocks Song Tap uses: a monotonic one for signal and motion timing, the wall clock for signed offers. */
interface SongTapClock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

/** The accelerometer, as "the phone was jerked at this time" (milliseconds on the monotonic clock). */
interface ImpulseSource {
    fun start(onImpulse: (Long) -> Unit)
    fun stop()

    /** The sensitivity profile whose jerk threshold decides what counts as a jerk (spec 6); may change while running. */
    fun setSensitivity(profile: SongTapSensitivity) {}
}
