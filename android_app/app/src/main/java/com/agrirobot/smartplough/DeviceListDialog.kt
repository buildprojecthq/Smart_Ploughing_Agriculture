package com.agrirobot.smartplough

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class DeviceListDialog(
    private val context: Context,
    private val devices: List<BluetoothDevice>,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) {

    fun show() {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Select Paired Bluetooth Device")

        if (devices.isEmpty()) {
            builder.setMessage("No paired devices found.\nPlease pair your HC-05 module in your phone's Bluetooth settings first.")
            builder.setPositiveButton("OK", null)
            builder.show()
            return
        }

        val adapter = DeviceAdapter(context, devices)
        builder.setAdapter(adapter) { dialog, which ->
            onDeviceSelected(devices[which])
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private class DeviceAdapter(
        context: Context,
        private val items: List<BluetoothDevice>
    ) : ArrayAdapter<BluetoothDevice>(context, R.layout.item_bluetooth_device, items) {

        @SuppressLint("MissingPermission")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_bluetooth_device, parent, false)

            val device = items[position]
            val tvName = view.findViewById<TextView>(R.id.tvDeviceName)
            val tvAddress = view.findViewById<TextView>(R.id.tvDeviceAddress)

            val name = device.name ?: "Unknown Device"
            tvName.text = name
            tvAddress.text = device.address

            // Highlight target robotic modules
            if (name.contains("HC-05", ignoreCase = true) || name.contains("HC-06", ignoreCase = true)) {
                tvName.setTextColor(context.getColor(R.color.primary_green))
            } else {
                tvName.setTextColor(context.getColor(R.color.text_primary))
            }

            return view
        }
    }
}

