package ua.agroguide.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val permissionRequest = 4001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(16, 24, 32)
        window.navigationBarColor = Color.rgb(16, 24, 32)
        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(16, 24, 32))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "AndroidGPSBridge")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun connect() { runOnUiThread { ensurePermissionThenChoose() } }
        @JavascriptInterface fun disconnect() { stopBluetooth("Відключено від HC-05") }
    }

    private fun ensurePermissionThenChoose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), permissionRequest)
        } else choosePairedDevice()
    }

    @SuppressLint("MissingPermission")
    private fun choosePairedDevice() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("Телефон не має Bluetooth"); return }
        if (!adapter.isEnabled) { toast("Увімкни Bluetooth у налаштуваннях телефона"); return }
        val devices = try { adapter.bondedDevices.toList().sortedBy { it.name ?: it.address } }
        catch (_: SecurityException) { toast("Немає дозволу Bluetooth"); return }
        if (devices.isEmpty()) { toast("Спочатку сполучи HC-05 у налаштуваннях Android"); return }
        val labels = devices.map { "${it.name ?: "Bluetooth-пристрій"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Вибери GPS-приймач HC-05")
            .setItems(labels) { _, which -> connectDevice(devices[which]) }
            .setNegativeButton("Скасувати", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        stopBluetooth(null)
        jsStatus(false, "Підключення до ${device.name ?: device.address}…")
        worker.execute {
            var localSocket: BluetoothSocket? = null
            try {
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                adapter.cancelDiscovery()
                localSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                localSocket.connect()
                socket = localSocket
                running = true
                jsStatus(true, "HC-05 підключено: ${device.name ?: device.address}. Очікую GPS-дані…")
                val input = localSocket.inputStream
                val bytes = ByteArray(1024)
                val pending = StringBuilder()
                while (running) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    for (ch in String(bytes, 0, count, Charsets.US_ASCII)) {
                        if (ch == '\n') {
                            val line = pending.toString().trim()
                            pending.setLength(0)
                            if (line.startsWith("$")) sendNmea(line)
                        } else if (ch != '\r' && pending.length < 300) pending.append(ch)
                    }
                }
                if (running) jsStatus(false, "З'єднання з HC-05 завершено")
            } catch (e: Exception) {
                jsStatus(false, "Не вдалося підключитися до HC-05: ${e.message ?: "перевір з'єднання"}")
            } finally {
                running = false
                try { localSocket?.close() } catch (_: Exception) {}
                if (socket === localSocket) socket = null
            }
        }
    }

    private fun sendNmea(line: String) {
        val quoted = org.json.JSONObject.quote(line)
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.receiveExternalNMEA && window.receiveExternalNMEA($quoted);", null) }
    }
    private fun jsStatus(connected: Boolean, detail: String) {
        val quoted = org.json.JSONObject.quote(detail)
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.setExternalGpsConnection && window.setExternalGpsConnection($connected, $quoted);", null) }
    }
    private fun stopBluetooth(message: String?) {
        running = false
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        if (message != null) jsStatus(false, message)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) choosePairedDevice()
            else toast("Потрібен дозвіл Bluetooth для HC-05")
        }
    }
    override fun onDestroy() {
        stopBluetooth(null)
        worker.shutdownNow()
        webView.removeJavascriptInterface("AndroidGPSBridge")
        webView.destroy()
        super.onDestroy()
    }
}