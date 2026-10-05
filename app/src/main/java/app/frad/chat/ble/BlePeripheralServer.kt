package app.frad.chat.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque

/**
 * The "available to chat" side of BLE presence: advertises a rotating session id
 * (never the long-term [app.frad.chat.crypto.Identity] key — see that
 * class for why) and runs a GATT server so a nearby central can connect and
 * exchange framed handshake/chat bytes.
 *
 * Requires BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT (API 31+) to be granted
 * before [start] is called; the caller (the UI/ViewModel layer that drives the
 * opt-in "broadcast" toggle) is responsible for that permission check.
 */
class BlePeripheralServer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCentralConnected(deviceAddress: String)
        fun onCentralDisconnected(deviceAddress: String)
        fun onFrameReceived(deviceAddress: String, frame: ByteArray)

        /** Advertising is up: others can find us. */
        fun onAdvertisingStarted()

        /** Advertising couldn't start ([errorCode] is an AdvertiseCallback.ADVERTISE_FAILED_*
         *  constant): we can still find others, but nobody can find us. */
        fun onAdvertisingFailed(errorCode: Int)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val reassemblers = mutableMapOf<String, FrameReassembler>()
    private val devicesByAddress = mutableMapOf<String, BluetoothDevice>()

    // A GATT server connection allows only one outstanding notification at a time — sending
    // the next fragment before onNotificationSent confirms the last one risks it being
    // silently dropped, same as the central side's writeCharacteristic. Only matters once a
    // message needs more than one fragment (e.g. MTU negotiation got refused), but a dropped
    // fragment there means the reassembler on the other end waits forever for bytes that are
    // never coming, so it's worth queuing properly rather than firing-and-forgetting.
    private val pendingNotifications = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val notifyInFlight = mutableMapOf<String, Boolean>()

    // GATT server callbacks land on binder threads while the controller calls in from its own
    // dispatcher; all the maps above (and [gattServer]/[advertiser]) are only touched under this
    // lock. Listener callbacks may be invoked while holding it - they just hand off to the
    // controller's dispatcher.
    private val lock = Any()

    /** @param sessionId a short-lived, rotating id — see [app.frad.chat.pairing]. */
    fun start(sessionId: ByteArray) {
        synchronized(lock) {
            require(sessionId.size <= GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES)

            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.also { server ->
                val service = BluetoothGattService(GattProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

                val inbox = BluetoothGattCharacteristic(
                    GattProfile.INBOX_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )

                val outbox = BluetoothGattCharacteristic(
                    GattProfile.OUTBOX_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
                outbox.addDescriptor(
                    BluetoothGattDescriptor(
                        GattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                        BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ,
                    ),
                )

                service.addCharacteristic(inbox)
                service.addCharacteristic(outbox)
                server.addService(service)
            }

            startAdvertisingLocked(sessionId)
        }
    }

    /** Swaps the advertised session id without touching the GATT server, so a connection that's
     *  just being set up isn't cut off - see BleChatController's periodic rotation. */
    fun rotateSessionId(sessionId: ByteArray) {
        synchronized(lock) {
            require(sessionId.size <= GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES)
            if (gattServer == null) return // not running
            advertiser?.stopAdvertising(advertiseCallback)
            startAdvertisingLocked(sessionId)
        }
    }

    /** Caller holds [lock]. */
    private fun startAdvertisingLocked(sessionId: ByteArray) {
        run {
            advertiser = adapter?.bluetoothLeAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID))
                .build()
            // The session id rides in the scan-response packet: a 128-bit service UUID
            // already consumes most of the 31-byte legacy advertisement budget.
            val scanResponse = AdvertiseData.Builder()
                .addServiceData(ParcelUuid(GattProfile.SERVICE_UUID), sessionId)
                .build()

            val activeAdvertiser = advertiser
            if (activeAdvertiser == null) {
                // No advertiser at all: Bluetooth is off, or this phone can't act as a BLE peripheral.
                listener.onAdvertisingFailed(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            } else {
                activeAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            }
        }
    }

    /** Forcibly ends a connection from the peripheral side, e.g. because the app is
     *  already busy with another chat or just handshook with a blocked peer. */
    fun disconnectDevice(deviceAddress: String) {
        synchronized(lock) {
            val device = devicesByAddress[deviceAddress] ?: return
            gattServer?.cancelConnection(device)
        }
    }

    fun stop() {
        synchronized(lock) {
            advertiser?.stopAdvertising(advertiseCallback)
            advertiser = null
            gattServer?.close()
            gattServer = null
            reassemblers.clear()
            devicesByAddress.clear()
            pendingNotifications.clear()
            notifyInFlight.clear()
        }
    }

    fun sendFrame(deviceAddress: String, message: ByteArray) {
        synchronized(lock) {
            // See BleCentralClient.DeviceConnection.fragmentSize for why MTU is never negotiated up
            // from this default - a central talking to this peripheral is always another FRAD
            // instance, so it never requests a larger MTU either.
            val queue = pendingNotifications.getOrPut(deviceAddress) { ArrayDeque() }
            for (fragment in FrameWriter.split(message, GattProfile.LEGACY_FRAGMENT_SIZE)) queue.add(fragment)
            pumpNotifyQueue(deviceAddress)
        }
    }

    /** Caller holds [lock]. */
    private fun pumpNotifyQueue(deviceAddress: String) {
        if (notifyInFlight[deviceAddress] == true) return
        val server = gattServer ?: return
        val device = devicesByAddress[deviceAddress] ?: return
        val service = server.getService(GattProfile.SERVICE_UUID) ?: return
        val outbox = service.getCharacteristic(GattProfile.OUTBOX_CHARACTERISTIC_UUID) ?: return
        val queue = pendingNotifications[deviceAddress] ?: return
        val next = queue.poll() ?: return

        notifyInFlight[deviceAddress] = true
        outbox.value = next
        @Suppress("DEPRECATION") // notifyCharacteristicChanged(device, characteristic, confirm) is the API available at minSdk 26
        server.notifyCharacteristicChanged(device, outbox, false)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            listener.onAdvertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertising failed to start: error $errorCode")
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) listener.onAdvertisingFailed(errorCode)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                Log.d(TAG, "onConnectionStateChange addr=${device.address} status=$status newState=$newState")
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    devicesByAddress[device.address] = device
                    reassemblers[device.address] = FrameReassembler()
                    listener.onCentralConnected(device.address)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    devicesByAddress.remove(device.address)
                    reassemblers.remove(device.address)
                    pendingNotifications.remove(device.address)
                    notifyInFlight.remove(device.address)
                    listener.onCentralDisconnected(device.address)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                notifyInFlight[device.address] = false
                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    // Same reasoning as the central side's onCharacteristicWrite: a dropped
                    // fragment would otherwise desync the peer's FrameReassembler forever, so
                    // disconnect cleanly instead of pumping the next fragment regardless.
                    gattServer?.cancelConnection(device)
                    return
                }
                pumpNotifyQueue(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            synchronized(lock) {
                var frameTooLarge = false
                try {
                    if (characteristic.uuid == GattProfile.INBOX_CHARACTERISTIC_UUID) {
                        Log.d(TAG, "onCharacteristicWriteRequest addr=${device.address} bytes=${value.size}")
                        val complete = try {
                            reassemblers.getOrPut(device.address) { FrameReassembler() }.offer(value)
                        } catch (e: FrameTooLargeException) {
                            Log.w(TAG, "dropping ${device.address}: ${e.message}")
                            frameTooLarge = true
                            emptyList()
                    }
                    complete.forEach { listener.onFrameReceived(device.address, it) }
                    }
                } finally {
                    // Always answer the write, even if handling it failed - otherwise the central's
                    // GATT queue stalls on it instead of seeing a clean disconnect.
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                }
                if (frameTooLarge) gattServer?.cancelConnection(device)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            // The central writes this descriptor to enable notifications right after service
            // discovery (see BleCentralClient.onServicesDiscovered) and waits for its own
            // onDescriptorWrite callback before proceeding to the handshake. Without a response
            // to this request, that write never completes at the ATT protocol level - the
            // central's callback never fires, and it sits on "Connecting" forever. This handler
            // was missing entirely, which is why every single connection attempt stalled at
            // exactly this point.
            Log.d(TAG, "onDescriptorWriteRequest addr=${device.address} uuid=${descriptor.uuid}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private companion object {
        const val TAG = "BlePeripheralServer"
    }
}
