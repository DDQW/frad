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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connections[gatt.device.address] = DeviceConnection(gatt)
                    gatt.requestMtu(GattProfile.DEFAULT_MTU)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connections.remove(gatt.device.address)
                    gatt.close()
                    listener.onDisconnected(gatt.device.address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val connection = connections[gatt.device.address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connection.fragmentSize = mtu - GattProfile.ATT_HEADER_SIZE
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val connection = connections[gatt.device.address] ?: return
            val service = gatt.getService(GattProfile.SERVICE_UUID) ?: return
            connection.inbox = service.getCharacteristic(GattProfile.INBOX_CHARACTERISTIC_UUID)
            val outbox = service.getCharacteristic(GattProfile.OUTBOX_CHARACTERISTIC_UUID)

            if (outbox != null) {
                gatt.setCharacteristicNotification(outbox, true)
                val cccd = outbox.getDescriptor(GattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }

            listener.onConnected(gatt.device.address)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val connection = connections[gatt.device.address] ?: return
            connection.writeInFlight = false
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
}
