package com.agrirobot.smartplough

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages Bluetooth Classic Serial Port Profile (SPP) connections to HC-05 / HC-06.
 */
class BluetoothManager private constructor() {

    companion object {
        @Volatile
        private var instance: BluetoothManager? = null

        fun getInstance(): BluetoothManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothManager().also { instance = it }
            }
        }

        // Standard SPP UUID for HC-05 / HC-06 modules
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    interface ConnectionListener {
        fun onConnecting()
        fun onConnected(deviceName: String, deviceAddress: String)
        fun onConnectionFailed(error: String)
        fun onDisconnected()
        fun onDataReceived(data: String)
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var connectionListener: ConnectionListener? = null
    var isConnected: Boolean = false
        private set

    fun setConnectionListener(listener: ConnectionListener) {
        this.connectionListener = listener
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        disconnect()

        mainHandler.post { connectionListener?.onConnecting() }
        connectThread = ConnectThread(device).apply { start() }
    }

    @Synchronized
    fun disconnect() {
        connectThread?.cancel()
        connectThread = null

        connectedThread?.cancel()
        connectedThread = null

        if (isConnected) {
            isConnected = false
            mainHandler.post { connectionListener?.onDisconnected() }
        }
    }

    fun sendCommand(cmd: Char): Boolean {
        return sendData(byteArrayOf(cmd.code.toByte()))
    }

    fun sendData(bytes: ByteArray): Boolean {
        val thread = connectedThread
        if (isConnected && thread != null) {
            return thread.write(bytes)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = null

        init {
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                mainHandler.post { connectionListener?.onConnectionFailed("Failed to create SPP socket: ${e.message}") }
            }
        }

        override fun run() {
            // Cancel discovery before connecting to speed up connection
            bluetoothAdapter?.cancelDiscovery()

            val currentSocket = socket ?: return

            try {
                currentSocket.connect()
            } catch (connectException: IOException) {
                try {
                    // Fallback for some Android builds: reflection invoke createRfcommSocket
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket?.connect()
                } catch (fallbackException: Exception) {
                    try {
                        currentSocket.close()
                    } catch (_: IOException) {}

                    mainHandler.post {
                        connectionListener?.onConnectionFailed("Unable to connect to ${device.name ?: "Device"}: ${connectException.message}")
                    }
                    return
                }
            }

            // Connection successful, initialize ConnectedThread
            synchronized(this@BluetoothManager) {
                connectThread = null
                val finalSocket = socket ?: return
                connectedThread = ConnectedThread(finalSocket, device).apply { start() }
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (_: IOException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectedThread(
        private val socket: BluetoothSocket,
        private val device: BluetoothDevice
    ) : Thread() {
        private val inStream: InputStream?
        private val outStream: OutputStream?

        init {
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null

            try {
                tmpIn = socket.inputStream
                tmpOut = socket.outputStream
            } catch (_: IOException) {}

            inStream = tmpIn
            outStream = tmpOut
            isConnected = true

            mainHandler.post {
                connectionListener?.onConnected(device.name ?: "HC-05", device.address)
            }
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (isConnected) {
                try {
                    bytes = inStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val incoming = String(buffer, 0, bytes)
                        mainHandler.post { connectionListener?.onDataReceived(incoming) }
                    } else if (bytes < 0) {
                        break
                    }
                } catch (_: IOException) {
                    break
                }
            }

            // Stream closed or error
            if (isConnected) {
                isConnected = false
                mainHandler.post { connectionListener?.onDisconnected() }
            }
        }

        fun write(bytes: ByteArray): Boolean {
            return try {
                outStream?.write(bytes)
                outStream?.flush()
                true
            } catch (_: IOException) {
                false
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (_: IOException) {}
        }
    }
}

