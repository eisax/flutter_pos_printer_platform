package com.sersoluciones.flutter_pos_printer_platform

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConnection
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConstants
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService
import com.sersoluciones.flutter_pos_printer_platform.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class FlutterPosPrinterPlatformPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {

    private val TAG = "FlutterPosPrinterPlatformPlugin"

    private var binaryMessenger: io.flutter.plugin.common.BinaryMessenger? = null
    private var channel: MethodChannel? = null
    private var messageChannel: EventChannel? = null
    private var messageUSBChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var eventUSBSink: EventChannel.EventSink? = null

    private var context: Context? = null
    private var currentActivity: Activity? = null
    private var requestPermissionBT: Boolean = false
    private var isBle: Boolean = false
    private var isScan: Boolean = false

    // Lazy initialization ensures adapter is only created when first accessed
    private val adapter: USBPrinterService by lazy { USBPrinterService.getInstance(usbHandler) }
    private lateinit var bluetoothService: BluetoothService

    private val usbHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                USBPrinterService.STATE_USB_CONNECTED -> eventUSBSink?.success(2)
                USBPrinterService.STATE_USB_CONNECTING -> eventUSBSink?.success(1)
                USBPrinterService.STATE_USB_NONE -> eventUSBSink?.success(0)
            }
        }
    }

    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {
        private val bluetoothStatus: Int
            get() = BluetoothService.bluetoothConnection?.state ?: 99

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    BluetoothConstants.MESSAGE_STATE_CHANGE -> {
                        when (bluetoothStatus) {
                            BluetoothConstants.STATE_CONNECTED -> {
                                eventSink?.success(2)
                                if (::bluetoothService.isInitialized) bluetoothService.removeReconnectHandlers()
                            }
                            BluetoothConstants.STATE_CONNECTING -> eventSink?.success(1)
                            BluetoothConstants.STATE_NONE -> {
                                eventSink?.success(0)
                                if (::bluetoothService.isInitialized) bluetoothService.autoConnectBt()
                            }
                            BluetoothConstants.STATE_FAILED -> eventSink?.success(0)
                        }
                    }
                    BluetoothConstants.MESSAGE_READ -> {
                        val readBuf = msg.obj as ByteArray
                        val readMessage = String(readBuf, 0, msg.arg1).trim()
                        Log.d(TAG, "Bluetooth read: $readMessage")
                    }
                    BluetoothConstants.MESSAGE_TOAST -> {
                        val bundle = msg.data
                        bundle?.getInt(BluetoothConnection.TOAST)?.let {
                            Toast.makeText(context, context?.getString(it), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth handler error: ${e.message}", e)
            }
        }
    }

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        binaryMessenger = binding.binaryMessenger
        channel = MethodChannel(binaryMessenger!!, methodChannel).also { it.setMethodCallHandler(this) }
        messageChannel = EventChannel(binaryMessenger!!, eventChannelBT)
        messageUSBChannel = EventChannel(binaryMessenger!!, eventChannelUSB)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        messageChannel?.setStreamHandler(null)
        messageUSBChannel?.setStreamHandler(null)
        if (::bluetoothService.isInitialized) bluetoothService.setHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        context = binding.activity.applicationContext
        currentActivity = binding.activity

        // USB setup
        try {
            requestUsbPermissions()
            adapter.init(context!!)
        } catch (e: Exception) {
            Log.e(TAG, "USB init failed: ${e.message}", e)
        }

        // Bluetooth setup
        bluetoothService = BluetoothService.getInstance(bluetoothHandler)
        bluetoothService.setActivity(currentActivity)

        // EventChannels after activity attached
        messageChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventSink = events }
            override fun onCancel(arguments: Any?) { eventSink = null }
        })

        messageUSBChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventUSBSink = events }
            override fun onCancel(arguments: Any?) { eventUSBSink = null }
        })

        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() { bluetoothService.setActivity(null); currentActivity = null }
    override fun onDetachedFromActivityForConfigChanges() { bluetoothService.setActivity(null); currentActivity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        bluetoothService.setActivity(currentActivity)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getBluetoothList" -> scanBluetooth(false, result)
                "getBluetoothLeList" -> scanBluetooth(true, result)
                "onStartConnection" -> startBluetoothConnection(call, result)
                "disconnect" -> disconnectBluetooth(result)
                "sendDataByte" -> sendBluetoothBytes(call, result)
                "sendText" -> sendBluetoothText(call, result)
                "getList" -> getUSBDeviceList(result)
                "connectPrinter" -> connectUSBPrinter(call, result)
                "close" -> closeUSBConnection(result)
                "printText" -> printUSBText(call, result)
                "printRawData" -> printUSBRaw(call, result)
                "printBytes" -> printUSBBytes(call, result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MethodCall error: ${e.message}", e)
            result.error("PLUGIN_ERROR", e.message, null)
        }
    }

    // ====== Bluetooth helpers ======
    private fun scanBluetooth(useBle: Boolean, result: MethodChannel.Result) {
        isScan = true
        isBle = useBle
        if (verifyBluetoothOn()) {
            if (useBle) bluetoothService.scanBleDevice(channel!!) else bluetoothService.scanBluDevice(channel!!)
        }
        result.success(null)
    }

    private fun startBluetoothConnection(call: MethodCall, result: MethodChannel.Result) {
        val address: String? = call.argument("address")
        val isBle: Boolean = call.argument("isBle") ?: false
        val autoConnect: Boolean = call.argument("autoConnect") ?: false
        if (verifyBluetoothOn()) {
            bluetoothService.setHandler(bluetoothHandler)
            bluetoothService.onStartConnection(context!!, address!!, result, isBle = isBle, autoConnect = autoConnect)
        } else result.success(false)
    }

    private fun disconnectBluetooth(result: MethodChannel.Result) {
        try { bluetoothService.setHandler(bluetoothHandler); bluetoothService.bluetoothDisconnect(); result.success(true) }
        catch (e: Exception) { result.success(false) }
    }

    private fun sendBluetoothBytes(call: MethodCall, result: MethodChannel.Result) {
        val listInt: ArrayList<Int>? = call.argument("bytes")
        if (listInt != null) {
            val bytes = listInt.foldIndexed(ByteArray(listInt.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
            result.success(bluetoothService.sendDataByte(bytes))
        } else result.success(false)
    }

    private fun sendBluetoothText(call: MethodCall, result: MethodChannel.Result) {
        call.argument<String>("text")?.let { bluetoothService.sendData(it) }
        result.success(true)
    }

    // ====== USB helpers ======
    private fun getUSBDeviceList(result: MethodChannel.Result) {
        val list = ArrayList<HashMap<*, *>>()
        try {
            if (::adapter.isInitialized) {
                adapter.deviceList.forEach { usbDevice ->
                    list.add(hashMapOf(
                        "name" to usbDevice.deviceName,
                        "manufacturer" to usbDevice.manufacturerName,
                        "product" to usbDevice.productName,
                        "deviceId" to usbDevice.deviceId.toString(),
                        "vendorId" to usbDevice.vendorId.toString(),
                        "productId" to usbDevice.productId.toString()
                    ))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "USB list error: ${e.message}", e) }
        result.success(list)
    }

    private fun connectUSBPrinter(call: MethodCall, result: MethodChannel.Result) {
        val vendor = call.argument<Int>("vendor")
        val product = call.argument<Int>("product")
        if (vendor == null || product == null) { result.success(false); return }
        if (::adapter.isInitialized) {
            adapter.setHandler(usbHandler)
            result.success(adapter.selectDevice(vendor, product))
        } else result.success(false)
    }

    private fun closeUSBConnection(result: MethodChannel.Result) { if (::adapter.isInitialized) adapter.closeConnectionIfExists(); result.success(true) }
    private fun printUSBText(call: MethodCall, result: MethodChannel.Result) { call.argument<String>("text")?.let { if (::adapter.isInitialized) adapter.printText(it) }; result.success(true) }
    private fun printUSBRaw(call: MethodCall, result: MethodChannel.Result) { call.argument<String>("raw")?.let { if (::adapter.isInitialized) adapter.printRawData(it) }; result.success(true) }
    private fun printUSBBytes(call: MethodCall, result: MethodChannel.Result) { call.argument<ArrayList<Int>>("bytes")?.let { if (::adapter.isInitialized) adapter.printBytes(it) }; result.success(true) }

    // ====== Bluetooth verification ======
    private fun verifyBluetoothOn(): Boolean {
        if (!checkPermissions()) return false
        if (!::bluetoothService.isInitialized) bluetoothService = BluetoothService.getInstance(bluetoothHandler)
        if (!bluetoothService.mBluetoothAdapter.isEnabled) {
            if (requestPermissionBT) return false
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            currentActivity?.let { ActivityCompat.startActivityForResult(it, enableBtIntent, PERMISSION_ENABLE_BLUETOOTH, null) }
            requestPermissionBT = true
            return false
        }
        return true
    }

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (!hasPermissions(context, *perms.toTypedArray())) {
            ActivityCompat.requestPermissions(currentActivity!!, perms.toTypedArray(), PERMISSION_ALL)
            return false
        }
        return true
    }

    private fun hasPermissions(context: Context?, vararg permissions: String?): Boolean {
        return context?.let { permissions.all { ActivityCompat.checkSelfPermission(it, it!!) == PackageManager.PERMISSION_GRANTED } } ?: false
    }

    private fun requestUsbPermissions() {
        val usbManager = context?.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val intent = PendingIntent.getBroadcast(context, 0, Intent("com.android.example.USB_PERMISSION"), 0)
        usbManager.deviceList.values.forEach { device ->
            if (!usbManager.hasPermission(device)) usbManager.requestPermission(device, intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == PERMISSION_ENABLE_BLUETOOTH) {
            requestPermissionBT = false
            if (resultCode == Activity.RESULT_OK && isScan) {
                if (isBle) bluetoothService.scanBleDevice(channel!!) else bluetoothService.scanBluDevice(channel!!)
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == PERMISSION_ALL) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (verifyBluetoothOn() && isScan) {
                    if (isBle) bluetoothService.scanBleDevice(channel!!) else bluetoothService.scanBluDevice(channel!!)
                }
            } else Toast.makeText(context, R.string.not_permissions, Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.sersoluciones.flutter_pos_printer_platform"
        const val eventChannelBT = "com.sersoluciones.flutter_pos_printer_platform/bt_state"
        const val eventChannelUSB = "com.sersoluciones.flutter_pos_printer_platform/usb_state"
    }
}
