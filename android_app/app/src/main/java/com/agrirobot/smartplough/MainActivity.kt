package com.agrirobot.smartplough

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BluetoothManager.ConnectionListener {

    private lateinit var bluetoothManager: BluetoothManager

    // UI Elements
    private lateinit var viewStatusDot: View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvLastPacket: TextView
    private lateinit var btnConnectToggle: Button

    private lateinit var btnForward: Button
    private lateinit var btnBackward: Button
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnEmergencyStop: Button

    private lateinit var switchPlough: SwitchCompat
    private lateinit var tvPloughState: TextView

    private lateinit var switchSeed: SwitchCompat
    private lateinit var tvSeedState: TextView

    private lateinit var switchWater: SwitchCompat
    private lateinit var tvWaterState: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Permission Request Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            openDeviceSelectionDialog()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to operate robot.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothManager = BluetoothManager.getInstance()
        bluetoothManager.setConnectionListener(this)

        initViews()
        setupLocomotionTouchListeners()
        setupToolSwitchListeners()
        setupConnectionControls()
    }

    private fun initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvLastPacket = findViewById(R.id.tvLastPacket)
        btnConnectToggle = findViewById(R.id.btnConnectToggle)

        btnForward = findViewById(R.id.btnForward)
        btnBackward = findViewById(R.id.btnBackward)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnEmergencyStop = findViewById(R.id.btnEmergencyStop)

        switchPlough = findViewById(R.id.switchPlough)
        tvPloughState = findViewById(R.id.tvPloughState)

        switchSeed = findViewById(R.id.switchSeed)
        tvSeedState = findViewById(R.id.tvSeedState)

        switchWater = findViewById(R.id.switchWater)
        tvWaterState = findViewById(R.id.tvWaterState)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLocomotionTouchListeners() {
        // Touch-and-Hold for Forward
        btnForward.setOnTouchListener { _, event ->
            handleDirectionalTouch(event, 'F')
        }

        // Touch-and-Hold for Backward
        btnBackward.setOnTouchListener { _, event ->
            handleDirectionalTouch(event, 'B')
        }

        // Touch-and-Hold for Skid Left
        btnLeft.setOnTouchListener { _, event ->
            handleDirectionalTouch(event, 'L')
        }

        // Touch-and-Hold for Skid Right
        btnRight.setOnTouchListener { _, event ->
            handleDirectionalTouch(event, 'R')
        }

        // Central Emergency STOP button
        btnEmergencyStop.setOnClickListener {
            transmitCommand('S')
        }
    }

    private fun handleDirectionalTouch(event: MotionEvent, commandByte: Char): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                transmitCommand(commandByte)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                transmitCommand('S')
                return true
            }
        }
        return false
    }

    private fun setupToolSwitchListeners() {
        // Plough Control: 'P' = Deploy, 'p' = Retract
        switchPlough.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                transmitCommand('P')
                tvPloughState.text = "State: DEPLOYED ('P')"
                tvPloughState.setTextColor(getColor(R.color.primary_green))
            } else {
                transmitCommand('p')
                tvPloughState.text = "State: RETRACTED ('p')"
                tvPloughState.setTextColor(getColor(R.color.text_secondary))
            }
        }

        // Seed Dispenser: 'D' = Agitate/Dispense, 'd' = Stop/Close
        switchSeed.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                transmitCommand('D')
                tvSeedState.text = "State: AGITATING / SOWING ('D')"
                tvSeedState.setTextColor(getColor(R.color.seed_amber))
            } else {
                transmitCommand('d')
                tvSeedState.text = "State: IDLE / CLOSED ('d')"
                tvSeedState.setTextColor(getColor(R.color.text_secondary))
            }
        }

        // Water Pump: 'W' = Pump ON, 'w' = Pump OFF
        switchWater.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                transmitCommand('W')
                tvWaterState.text = "State: PUMP ACTIVE ('W')"
                tvWaterState.setTextColor(getColor(R.color.water_blue))
            } else {
                transmitCommand('w')
                tvWaterState.text = "State: PUMP OFF ('w')"
                tvWaterState.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun setupConnectionControls() {
        btnConnectToggle.setOnClickListener {
            if (bluetoothManager.isConnected) {
                bluetoothManager.disconnect()
            } else {
                checkPermissionsAndConnect()
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        if (!bluetoothManager.isBluetoothSupported()) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_SHORT).show()
            return
        }

        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            openDeviceSelectionDialog()
        }
    }

    private fun openDeviceSelectionDialog() {
        val pairedDevices = bluetoothManager.getPairedDevices().toList()
        DeviceListDialog(this, pairedDevices) { selectedDevice ->
            bluetoothManager.connect(selectedDevice)
        }.show()
    }

    private fun transmitCommand(cmd: Char) {
        val timestamp = timeFormat.format(Date())
        if (bluetoothManager.isConnected) {
            bluetoothManager.sendCommand(cmd)
            tvLastPacket.text = "Sent: '$cmd' @ $timestamp"
        } else {
            tvLastPacket.text = "Offline Command: '$cmd' @ $timestamp"
        }
    }

    // ========================================================================
    // Bluetooth Connection Callbacks
    // ========================================================================

    override fun onConnecting() {
        viewStatusDot.setBackgroundResource(R.drawable.bg_dot_connecting)
        tvConnectionStatus.text = getString(R.string.status_connecting)
        btnConnectToggle.isEnabled = false
    }

    override fun onConnected(deviceName: String, deviceAddress: String) {
        viewStatusDot.setBackgroundResource(R.drawable.bg_dot_connected)
        tvConnectionStatus.text = getString(R.string.status_connected, deviceName)
        btnConnectToggle.text = getString(R.string.btn_disconnect)
        btnConnectToggle.setBackgroundColor(getColor(R.color.stop_red_dark))
        btnConnectToggle.isEnabled = true
        Toast.makeText(this, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionFailed(error: String) {
        viewStatusDot.setBackgroundResource(R.drawable.bg_dot_disconnected)
        tvConnectionStatus.text = getString(R.string.status_disconnected)
        btnConnectToggle.text = getString(R.string.btn_connect)
        btnConnectToggle.setBackgroundColor(getColor(R.color.primary_green_dark))
        btnConnectToggle.isEnabled = true
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    override fun onDisconnected() {
        viewStatusDot.setBackgroundResource(R.drawable.bg_dot_disconnected)
        tvConnectionStatus.text = getString(R.string.status_disconnected)
        btnConnectToggle.text = getString(R.string.btn_connect)
        btnConnectToggle.setBackgroundColor(getColor(R.color.primary_green_dark))
        btnConnectToggle.isEnabled = true
        Toast.makeText(this, "Bluetooth link disconnected.", Toast.LENGTH_SHORT).show()
    }

    override fun onDataReceived(data: String) {
        // Handle incoming telemetry or debug messages from Arduino Uno
        tvLastPacket.text = "Rx: ${data.trim()}"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Failsafe: stop motors before teardown
        if (bluetoothManager.isConnected) {
            bluetoothManager.sendCommand('S')
            bluetoothManager.sendCommand('p')
            bluetoothManager.sendCommand('d')
            bluetoothManager.sendCommand('w')
        }
        bluetoothManager.disconnect()
    }
}
