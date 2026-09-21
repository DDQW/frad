package me.woelki.frad.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque

/**
 * The "browsing for someone to chat with" side of BLE presence: scans for peers
 * currently advertising [GattProfile.SERVICE_UUID], and once the app picks one
 * to chat with (see [me.woelki.frad.pairing.RandomMatcher]), connects as
 * a GATT client and exchanges framed handshake/chat bytes.
 *
 * Requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) to be granted before
 * [startScanning] / [connect] are called.
 */
class BleCentralClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeerDiscovered(deviceAddress: String, sessionId: ByteArray, rssi: Int)
        fun onConnected(deviceAddress: String)
        fun onDisconnected(deviceAddress: String)
        fun onFrameReceived(deviceAddress: String, frame: ByteArray)
    }

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    private var scanner: BluetoothLeScanner? = null
    private val connections = mutableMapOf<String, DeviceConnection>()

    private class DeviceConnection(val gatt: BluetoothGatt) {
        val reassembler = FrameReassembler()
        val pendingWrites: ArrayDeque<ByteArray> = ArrayDeque()
        var writeInFlight = false
        // Deliberately never negotiated up via requestMtu(): a GATT connection only allows one
        // operation in flight at a time, and MTU negotiation's own callback (onMtuChanged) is a
        // well-known flaky spot that doesn't reliably fire on every device/OEM. There is no gap
        // in this connection's setup sequence where issuing it wouldn't risk delaying or
        // silently dropping whatever GATT operation comes right after it - which, unlike MTU
        // negotiation itself, is never optional (service discovery, the descriptor write, every
        // handshake/chat fragment write). This legacy size is small enough to always be safe
        // without negotiation, at the cost of needing more fragments for larger messages.
        var fragmentSize = GattProfile.LEGACY_FRAGMENT_SIZE
        var inbox: BluetoothGattCharacteristic? = null
    }

    fun startScanning() {
        scanner = adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
    }

    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() -> $deviceAddress")
        val device = adapter?.getRemoteDevice(deviceAddress) ?: return
        device.connectGatt(context, false, gattCallback)
    }

    fun disconnect(deviceAddress: String) {
        connections[deviceAddress]?.gatt?.disconnect()
    }

    fun sendFrame(deviceAddress: String, message: ByteArray) {
        val connection = connections[deviceAddress] ?: return
        for (fragment in FrameWriter.split(message, connection.fragmentSize)) {
            connection.pendingWrites.add(fragment)
        }
        pumpWriteQueue(deviceAddress)
    }

    private fun pumpWriteQueue(deviceAddress: String) {
        val connection = connections[deviceAddress] ?: return
        if (connection.writeInFlight) return
        val inbox = connection.inbox ?: return
        val next = connection.pendingWrites.poll() ?: return

        connection.writeInFlight = true
        inbox.value = next
        @Suppress("DEPRECATION") // BluetoothGattCharacteristic#setValue+writeCharacteristic(characteristic) is the API available at minSdk 26
        connection.gatt.writeCharacteristic(inbox)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val sessionId = result.scanRecord?.getServiceData(ParcelUuid(GattProfile.SERVICE_UUID)) ?: return
            listener.onPeerDiscovered(result.device.address, sessionId, result.rssi)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange addr=${gatt.device.address} status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connections[gatt.device.address] = DeviceConnection(gatt)
                    // The one GATT operation everything else here depends on, so it's issued
                    // immediately while the queue is guaranteed idle (right after connecting) -
                    // see onServicesDiscovered for why MTU negotiation deliberately isn't in
                    // this critical path at all any more.
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connections.remove(gatt.device.address)
                    gatt.close()
                    listener.onDisconnected(gatt.device.address)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val connection = connections[gatt.device.address] ?: return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(GattProfile.SERVICE_UUID) else null
            val outbox = service?.getCharacteristic(GattProfile.OUTBOX_CHARACTERISTIC_UUID)
            val inbox = service?.getCharacteristic(GattProfile.INBOX_CHARACTERISTIC_UUID)
            Log.d(TAG, "onServicesDiscovered addr=${gatt.device.address} status=$status service=${service != null} inbox=${inbox != null} outbox=${outbox != null}")
            if (service == null || outbox == null || inbox == null) {
                // Service discovery failed, or this peer's GATT server doesn't actually expose
                // what we expect - press on and the peer would just sit there waiting for a
                // handshake byte that's never coming. Disconnect cleanly instead; the resulting
                // onConnectionStateChange(DISCONNECTED) surfaces it as a normal failed attempt.
                gatt.disconnect()
                return
            }
            connection.inbox = inbox
            gatt.setCharacteristicNotification(outbox, true)
            val cccd = outbox.getDescriptor(GattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID)

            if (cccd != null) {
                // A GATT connection only ever has one operation in flight at a time, so the
                // Noise handshake's first message (sent once onConnected fires) has to wait
                // for this descriptor write to actually complete — see onDescriptorWrite —
                // rather than firing right after this call returns. Issuing the characteristic
                // write while this one is still pending gets it silently dropped by the stack,
                // which used to strand the handshake before its first byte ever went out.
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            } else {
                listener.onConnected(gatt.device.address)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != GattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            Log.d(TAG, "onDescriptorWrite addr=${gatt.device.address} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            listener.onConnected(gatt.device.address)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val connection = connections[gatt.device.address] ?: return
            connection.writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite FAILED addr=${gatt.device.address} status=$status")
                // A dropped fragment would otherwise desync the peer's FrameReassembler forever
                // (it keeps waiting for bytes that already silently failed to send) - disconnect
                // cleanly instead of pumping the next fragment as if this one had gone through.
                gatt.disconnect()
                return
            }
            pumpWriteQueue(gatt.device.address)
        }

        @Suppress("DEPRECATION") // onCharacteristicChanged(gatt, characteristic) is the API available at minSdk 26
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != GattProfile.OUTBOX_CHARACTERISTIC_UUID) return
            val connection = connections[gatt.device.address] ?: return
            val complete = connection.reassembler.offer(characteristic.value ?: return)
            if (complete != null) {
                listener.onFrameReceived(gatt.device.address, complete)
            }
        }
    }

    private companion object {
        const val TAG = "BleCentralClient"
    }
}
