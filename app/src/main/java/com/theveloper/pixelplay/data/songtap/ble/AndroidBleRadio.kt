package com.theveloper.pixelplay.data.songtap.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapWire
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Bluetooth LE on this phone, for Song Tap: advertise one service UUID, scan for both, run the sender's GATT
 * server, and be a client of somebody else's. Every decision is in [BleTransport]; this class only turns
 * platform callbacks into [BleRadioListener] calls and back, and keeps the GATT client's one-operation-at-a-time rule.
 *
 * Nothing is created until [start]. The advertisement is the service UUID alone: no name, no data, no TX
 * power, and the address Android gives an advertiser is a private one that changes every few minutes.
 */
@SuppressLint("MissingPermission")
@Singleton
class AndroidBleRadio @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleRadio {

    // Lock order: this lock is never held while calling the listener (the transport takes its own lock and calls back
    // into this class), so every call to [listener] is made after the lock is released.
    private val lock = Any()
    @Volatile private var listener: BleRadioListener? = null
    private var role = Role.SEND
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var server: BluetoothGattServer? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var running = false
    private var stateReceiver: BroadcastReceiver? = null

    private val devices = HashMap<String, BluetoothDevice>()
    private val clients = HashMap<String, ClientConnection>()
    private val serverClients = HashMap<String, BluetoothDevice>()
    private val offerCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // ── Start and stop ────────────────────────────────────────────────────────────────

    override fun start(role: Role, listener: BleRadioListener): BleStatus = synchronized(lock) {
        stopLocked()
        this.listener = listener
        this.role = role
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BleStatus.UNSUPPORTED
        if (!hasPermissions()) return BleStatus.NO_PERMISSION
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return BleStatus.UNSUPPORTED
        val adapter = manager.adapter ?: return BleStatus.UNSUPPORTED
        this.adapter = adapter
        registerStateReceiver()
        if (!adapter.isEnabled) return BleStatus.BLUETOOTH_OFF
        val result = startRadiosLocked(manager, adapter)
        if (result != BleStatus.ACTIVE) stopLocked(keepListener = true)
        result
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_ADVERTISE) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startRadiosLocked(manager: BluetoothManager, adapter: BluetoothAdapter): BleStatus {
        return try {
            val adv = adapter.bluetoothLeAdvertiser ?: return BleStatus.UNSUPPORTED
            val scan = adapter.bluetoothLeScanner ?: return BleStatus.BLUETOOTH_OFF
            advertiser = adv
            scanner = scan
            running = true
            if (role == Role.SEND) openServerLocked(manager) else startAdvertisingLocked()
            startScanLocked(scan)
            BleStatus.ACTIVE
        } catch (e: SecurityException) {
            Timber.w(e, "song tap: bluetooth permission missing")
            BleStatus.NO_PERMISSION
        } catch (e: IllegalStateException) {
            Timber.w(e, "song tap: bluetooth not ready")
            BleStatus.BLUETOOTH_OFF
        }
    }

    private fun stopLocked(keepListener: Boolean = false) {
        running = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        for (c in clients.values) c.close()
        clients.clear()
        try { server?.close() } catch (_: Exception) { }
        server = null
        eventCharacteristic = null
        serverClients.clear()
        offerCache.clear()
        devices.clear()
        scanner = null
        advertiser = null
        stateReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        stateReceiver = null
        adapter = null
        if (!keepListener) listener = null
    }

    private fun registerStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        synchronized(lock) { stopRadiosOnlyLocked() }
                        listener?.onStatus(BleStatus.BLUETOOTH_OFF)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        var up = false
                        synchronized(lock) {
                            val manager = context.getSystemService(BluetoothManager::class.java)
                            val a = manager?.adapter
                            if (listener != null && manager != null && a != null && !running) {
                                up = startRadiosLocked(manager, a) == BleStatus.ACTIVE
                            }
                        }
                        if (up) listener?.onStatus(BleStatus.ACTIVE)
                    }
                }
            }
        }
        stateReceiver = receiver
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Bluetooth was switched off: the radios are gone but the listener and the state receiver stay for the switch back on. */
    private fun stopRadiosOnlyLocked() {
        running = false
        for (c in clients.values) c.close()
        clients.clear()
        try { server?.close() } catch (_: Exception) { }
        server = null
        eventCharacteristic = null
        serverClients.clear()
        offerCache.clear()
        devices.clear()
        scanner = null
        advertiser = null
    }

    // ── Advertising and scanning ──────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Timber.w("song tap: advertising failed (%d)", errorCode)
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) listener?.onStatus(BleStatus.UNSUPPORTED)
        }
    }

    private fun startAdvertisingLocked() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(role == Role.SEND)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(SongTapWire.serviceOf(role))))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            var seen: Role? = null
            for (u in uuids) {
                val r = SongTapWire.roleOfService(u.uuid.toString().lowercase())
                if (r != null) { seen = r; break }
            }
            val peerRole = seen ?: return
            val address = result.device.address
            val l: BleRadioListener?
            synchronized(lock) {
                if (!running) return
                devices[address] = result.device
                l = listener
            }
            l?.onSeen(address, peerRole, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.w("song tap: scan failed (%d)", errorCode)
            listener?.onStatus(BleStatus.UNSUPPORTED)
        }
    }

    private fun startScanLocked(scan: BluetoothLeScanner) {
        val filters = listOf(SongTapWire.SERVICE_SEND, SongTapWire.SERVICE_RECEIVE).map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(it))).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        scan.startScan(filters, settings, scanCallback)
    }

    // ── The sender's GATT server ──────────────────────────────────────────────────────

    private fun openServerLocked(manager: BluetoothManager) {
        val s = manager.openGattServer(context, serverCallback) ?: throw IllegalStateException("no GATT server")
        server = s
        val service = BluetoothGattService(UUID.fromString(SongTapWire.SERVICE_SEND), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(UUID.fromString(SongTapWire.CHAR_INFO), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(UUID.fromString(SongTapWire.CHAR_OFFER), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(UUID.fromString(SongTapWire.CHAR_CONTROL), BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        val event = BluetoothGattCharacteristic(
            UUID.fromString(SongTapWire.CHAR_EVENT), BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        event.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(event)
        eventCharacteristic = event
        if (!s.addService(service)) throw IllegalStateException("could not add the service")
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            var failure: BleStatus? = null
            synchronized(lock) {
                if (!running || role != Role.SEND) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try { startAdvertisingLocked() } catch (e: SecurityException) { failure = BleStatus.NO_PERMISSION }
                } else {
                    failure = BleStatus.UNSUPPORTED
                }
            }
            failure?.let { listener?.onStatus(it) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) { serverClients[address] = device }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) { serverClients.remove(address) }
                offerCache.remove(address)
                listener?.serverClientGone(address)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val address = device.address
            val l = listener
            val full: ByteArray? = when (characteristic.uuid) {
                U_INFO -> l?.serverInfo()
                U_OFFER -> {
                    // A long read comes as several requests with growing offsets: sign once, on the first.
                    if (offset == 0) {
                        val f = l?.serverOffer(address)
                        if (f != null) offerCache[address] = f else offerCache.remove(address)
                        f
                    } else {
                        offerCache[address]
                    }
                }
                else -> null
            }
            try {
                when {
                    full == null -> server?.sendResponse(device, requestId, SongTapWire.ERROR_NOT_READY, offset, null)
                    offset > full.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    else -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
                }
            } catch (e: SecurityException) {
                Timber.w(e, "song tap: could not answer a read")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = characteristic.uuid == U_CONTROL && !preparedWrite && offset == 0 && value != null &&
                (listener?.serverControl(device.address, value) == true)
            if (responseNeeded) {
                try {
                    server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else SongTapWire.ERROR_NOT_READY, offset, null)
                } catch (e: SecurityException) {
                    Timber.w(e, "song tap: could not answer a write")
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) {
                try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) } catch (_: SecurityException) { }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            try {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            } catch (_: SecurityException) { }
        }
    }

    override fun notifyGo(client: String) {
        synchronized(lock) {
            val device = serverClients[client] ?: return
            val s = server ?: return
            val characteristic = eventCharacteristic ?: return
            val go = SongTapWire.encodeGo()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    s.notifyCharacteristicChanged(device, characteristic, false, go)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.value = go
                        s.notifyCharacteristicChanged(device, characteristic, false)
                    }
                }
            } catch (e: SecurityException) {
                Timber.w(e, "song tap: could not notify")
            }
        }
    }

    override fun dropClient(client: String) {
        synchronized(lock) {
            val device = serverClients[client] ?: return
            try { server?.cancelConnection(device) } catch (_: SecurityException) { }
        }
    }

    // ── The receiver's client side ────────────────────────────────────────────────────

    private sealed interface Op {
        data object ReadInfo : Op
        data object ReadOffer : Op
        class Write(val value: ByteArray) : Op
    }

    private inner class ClientConnection(val address: String, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var ready = false
        var busy = false
        var info: BluetoothGattCharacteristic? = null
        var offer: BluetoothGattCharacteristic? = null
        var control: BluetoothGattCharacteristic? = null
        var event: BluetoothGattCharacteristic? = null
        val queue = ArrayDeque<Op>()

        fun close() {
            try { gatt?.disconnect() } catch (_: Exception) { }
            try { gatt?.close() } catch (_: Exception) { }
            gatt = null
        }
    }

    override fun connect(address: String) {
        var failed: BleRadioListener? = null
        synchronized(lock) {
            val device = devices[address]
            if (!running || device == null) {
                failed = listener
            } else if (clients[address] == null) {
                val c = ClientConnection(address, device)
                val g = try {
                    device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    Timber.w(e, "song tap: could not connect")
                    null
                }
                if (g == null) {
                    failed = listener
                } else {
                    c.gatt = g
                    clients[address] = c
                }
            }
        }
        // A connection that could not even start is reported after the lock is released: the caller holds its own.
        failed?.onConnected(address, false)
    }

    override fun disconnect(address: String) {
        synchronized(lock) {
            val c = clients.remove(address) ?: return
            c.close()
        }
    }

    override fun readInfo(address: String) = enqueue(address, Op.ReadInfo)

    override fun readOffer(address: String) = enqueue(address, Op.ReadOffer)

    override fun writeControl(address: String, value: ByteArray) = enqueue(address, Op.Write(value))

    private fun enqueue(address: String, op: Op) {
        var failed: BleRadioListener? = null
        synchronized(lock) {
            val c = clients[address]
            if (c == null) failed = listener else {
                c.queue.addLast(op)
                pumpLocked(c)
            }
        }
        // No connection: answer as a failed operation, outside the lock.
        failed?.let { fail(it, address, op) }
    }

    private fun fail(l: BleRadioListener, address: String, op: Op) {
        when (op) {
            Op.ReadInfo -> l.onInfoRead(address, null)
            Op.ReadOffer -> l.onOfferRead(address, null)
            is Op.Write -> l.onControlWritten(address, false)
        }
    }

    /** Runs the next queued operation if none is in flight. Must hold [lock]. */
    private fun pumpLocked(c: ClientConnection) {
        while (c.ready && !c.busy) {
            val op = c.queue.removeFirstOrNull() ?: return
            val g = c.gatt ?: return
            val started = try {
                when (op) {
                    Op.ReadInfo -> c.info?.let { g.readCharacteristic(it) } ?: false
                    Op.ReadOffer -> c.offer?.let { g.readCharacteristic(it) } ?: false
                    is Op.Write -> c.control?.let { writeCharacteristic(g, it, op.value) } ?: false
                }
            } catch (e: SecurityException) {
                false
            }
            if (started) { c.busy = true; return }
            // Could not even start: report it after this call returns (the listener may call back into us).
            val l = listener
            if (l != null) failLater(l, c.address, op)
        }
    }

    private fun failLater(l: BleRadioListener, address: String, op: Op) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { fail(l, address, op) }
    }

    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                g.writeCharacteristic(ch)
            }
        }

    private fun connectionFor(gatt: BluetoothGatt): ClientConnection? = synchronized(lock) { clients[gatt.device.address] }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (!gatt.requestMtu(SongTapWire.WANTED_MTU)) gatt.discoverServices()
                } catch (e: SecurityException) {
                    gatt.disconnect()
                }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val wasReady: Boolean
                val l: BleRadioListener?
                synchronized(lock) {
                    val c = clients.remove(address)
                    wasReady = c?.ready == true
                    c?.ready = false
                    l = listener
                }
                try { gatt.close() } catch (_: Exception) { }
                if (l != null) if (wasReady) l.onDisconnected(address) else l.onConnected(address, false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try { gatt.discoverServices() } catch (_: SecurityException) { gatt.disconnect() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val c = connectionFor(gatt) ?: return
            val service = gatt.getService(U_SERVICE_SEND)
            val event = service?.getCharacteristic(U_EVENT)
            val cccd = event?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null || event == null || cccd == null) {
                gatt.disconnect()
                return
            }
            synchronized(lock) {
                c.info = service.getCharacteristic(U_INFO)
                c.offer = service.getCharacteristic(U_OFFER)
                c.control = service.getCharacteristic(U_CONTROL)
                c.event = event
            }
            if (c.info == null || c.offer == null || c.control == null) { gatt.disconnect(); return }
            try {
                gatt.setCharacteristicNotification(event, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
            } catch (e: SecurityException) {
                gatt.disconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD) return
            val c = connectionFor(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return }
            val l: BleRadioListener?
            synchronized(lock) {
                c.ready = true
                c.busy = false
                l = listener
            }
            l?.onConnected(c.address, true)
            synchronized(lock) { pumpLocked(c) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleRead(gatt, characteristic.uuid, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(gatt, characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val c = connectionFor(gatt) ?: return
            val l: BleRadioListener?
            synchronized(lock) { c.busy = false; l = listener }
            l?.onControlWritten(c.address, status == BluetoothGatt.GATT_SUCCESS)
            synchronized(lock) { pumpLocked(c) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleChanged(gatt, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleChanged(gatt, characteristic.uuid, value)
        }
    }

    private fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
        val c = connectionFor(gatt) ?: return
        val l: BleRadioListener?
        synchronized(lock) { c.busy = false; l = listener }
        val bytes = if (status == BluetoothGatt.GATT_SUCCESS) value else null
        when (uuid) {
            U_INFO -> l?.onInfoRead(c.address, bytes)
            U_OFFER -> l?.onOfferRead(c.address, bytes)
        }
        synchronized(lock) { pumpLocked(c) }
    }

    private fun handleChanged(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?) {
        if (value == null || uuid != U_EVENT) return
        val c = connectionFor(gatt) ?: return
        listener?.onNotified(c.address, value)
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val U_INFO: UUID = UUID.fromString(SongTapWire.CHAR_INFO)
        val U_OFFER: UUID = UUID.fromString(SongTapWire.CHAR_OFFER)
        val U_CONTROL: UUID = UUID.fromString(SongTapWire.CHAR_CONTROL)
        val U_EVENT: UUID = UUID.fromString(SongTapWire.CHAR_EVENT)
        val U_SERVICE_SEND: UUID = UUID.fromString(SongTapWire.SERVICE_SEND)
    }
}
