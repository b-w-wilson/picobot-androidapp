/*

 * Copyright 2024 Bruce W. Wilson
 */

package com.hwrobotics.picobotapp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.hwrobotics.picobotapp.databinding.ActivityRobotControlBinding
import com.hwrobotics.picobotapp.ble.ConnectionEventListener
import com.hwrobotics.picobotapp.ble.ConnectionManager
import com.hwrobotics.picobotapp.ble.ConnectionManager.parcelableExtraCompat
import com.hwrobotics.picobotapp.ble.isIndicatable
import com.hwrobotics.picobotapp.ble.isNotifiable
import com.hwrobotics.picobotapp.ble.isReadable
import com.hwrobotics.picobotapp.ble.isWritable
import com.hwrobotics.picobotapp.ble.isWritableWithoutResponse
import com.hwrobotics.picobotapp.ble.toHexString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


class RobotControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRobotControlBinding
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")
    }

    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }
    private val characteristicProperties by lazy {
        characteristics.associateWith { characteristic ->
            mutableListOf<CharacteristicProperty>().apply {
                if (characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                if (characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                if (characteristic.isReadable()) add(CharacteristicProperty.Readable)
                if (characteristic.isWritable()) add(CharacteristicProperty.Writable)
                if (characteristic.isWritableWithoutResponse()) {
                    add(CharacteristicProperty.WritableWithoutResponse)
                }
            }.toList()
        }
    }

    private val notifyingCharacteristics = mutableListOf<UUID>()

    lateinit var mainHandler: Handler
    var special_1_click_toggle = false
    var special_2_click_toggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.registerListener(connectionEventListener)

        binding = ActivityRobotControlBinding.inflate(layoutInflater)

        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.robot_control)
        }
//        setupRecyclerView()

        binding.activityRobotControlSpecial1Btn?.setOnClickListener {
            special_1_click_toggle = true
        }

        binding.activityRobotControlSpecial1Btn?.setOnClickListener {
            special_2_click_toggle = true
        }

        mainHandler = Handler(Looper.getMainLooper())
    }


    private val updateTextTask = object : Runnable {
        override fun run() {
            val strength = binding.activityRobotControlJoystick?.strength?.coerceAtMost(100)

            if (strength != null) {
                if(strength > 0) {
                    val characteristic = characteristics.find { it.isWritable() && it.uuid.toString().startsWith("00002A6E", ignoreCase = true) }
                    if (characteristic != null) {

                        val packet = binding.activityRobotControlJoystick?.angle.toString() + "|" + strength

                        ConnectionManager.writeCharacteristic(device, characteristic, packet.toByteArray())
                    }
                }
            }

            if(special_1_click_toggle) {
                //TODO write special click
                special_1_click_toggle = false
            }
            if(special_2_click_toggle) {
                //TODO write special click
                special_2_click_toggle = false
            }

            mainHandler.postDelayed(this, 200)
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        mainHandler.removeCallbacks(updateTextTask)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(updateTextTask)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(updateTextTask)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

//    private fun setupRecyclerView() {
//        binding.characteristicsRecyclerView.apply {
//            adapter = characteristicAdapter
//            layoutManager = LinearLayoutManager(
//                this@RobotControlActivity,
//                RecyclerView.VERTICAL,
//                false
//            )
//            isNestedScrollingEnabled = false
//
//            itemAnimator.let {
//                if (it is SimpleItemAnimator) {
//                    it.supportsChangeAnimations = false
//                }
//            }
//        }
//    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = "${dateFormatter.format(Date())}: $message"
//        runOnUiThread {
//            val uiText = binding.logTextView.text
//            val currentLogText = uiText.ifEmpty { "Beginning of log." }
//            binding.logTextView.text = "$currentLogText\n$formattedMessage"
//            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
//        }
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    AlertDialog.Builder(this@RobotControlActivity)
                        .setTitle("Disconnected")
                        .setMessage("Disconnected from device.")
                        .setPositiveButton("OK") { _, _ -> onBackPressed() }
                        .show()
                }
            }

            onCharacteristicRead = { _, characteristic, value ->
                log("Read from ${characteristic.uuid}: ${value.toHexString()}")
            }

            onCharacteristicWrite = { _, characteristic ->
                log("Wrote to ${characteristic.uuid}")
            }

            onMtuChanged = { _, mtu ->
                log("MTU updated to $mtu")
            }

            onCharacteristicChanged = { _, characteristic, value ->
                log("Value changed on ${characteristic.uuid}: ${value.toHexString()}")
            }

            onNotificationsEnabled = { _, characteristic ->
                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }

    private enum class CharacteristicProperty {
        Readable,
        Writable,
        WritableWithoutResponse,
        Notifiable,
        Indicatable;

        val action
            get() = when (this) {
                Readable -> "Read"
                Writable -> "Write"
                WritableWithoutResponse -> "Write Without Response"
                Notifiable -> "Toggle Notifications"
                Indicatable -> "Toggle Indications"
            }
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.uppercase(Locale.US).toInt(16).toByte() }.toByteArray()
}
