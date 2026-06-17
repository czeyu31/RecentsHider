package com.example.hiderecents

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class HeartRateActivity : AppCompatActivity() {

    companion object {
        private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private const val REQUEST_BLE = 1001
        
        // 静态变量存储心率数据
        var currentHeartRate: Int = 0
            private set
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connected = false

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var heartView: HeartView
    private lateinit var particleView: ParticleView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            AppLogger.init(this)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
            setContentView(R.layout.activity_heart_rate)

            tvStatus = findViewById(R.id.tvStatus)
            tvDeviceName = findViewById(R.id.tvDeviceName)
            heartView = findViewById(R.id.heartView)
            particleView = findViewById(R.id.particleView)

            particleView.start()

            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = btManager.adapter
            if (bluetoothAdapter == null) { tvStatus.text = "设备不支持蓝牙"; return }
            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_BLE)
                return
            }
            startScan()
        } catch (e: Exception) { AppLogger.e("HeartRate: CRASH", e) }
    }

    private fun hasPermissions() = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == REQUEST_BLE && g.all { it == PackageManager.PERMISSION_GRANTED }) startScan() else tvStatus.text = "需要蓝牙权限"
    }

    private fun startScan() {
        if (scanning) return
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        scanning = true; tvStatus.text = "扫描心率设备中..."
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed({ if (scanning && !connected) { stopScan(); tvStatus.text = "未找到心率设备" } }, 15000)
    }

    private fun stopScan() { if (!scanning) return; scanning = false; if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner?.stopScan(scanCallback) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(ct: Int, r: ScanResult) {
            if (connected) return
            val d = r.device; val n = d.name ?: r.scanRecord?.deviceName ?: return
            val hr = r.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE_UUID } == true
            if (hr || n.lowercase().let { it.contains("heart") || it.contains("band") || it.contains("mi ") || it.contains("honor") || it.contains("huawei") || it.contains("watch") || it.contains("miband") || it.contains("redmi") }) {
                stopScan(); connected = true
                handler.post { tvStatus.text = "已连接"; tvDeviceName.text = n }
                if (ActivityCompat.checkSelfPermission(this@HeartRateActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                d.connectGatt(this@HeartRateActivity, false, gattCallback)
            }
        }
        override fun onScanFailed(e: Int) { handler.post { tvStatus.text = "扫描失败: $e" } }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
            if (ns == BluetoothGatt.STATE_CONNECTED) {
                handler.post { tvStatus.text = "发现服务中..." }
                if (ActivityCompat.checkSelfPermission(this@HeartRateActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                g.discoverServices()
            } else if (ns == BluetoothGatt.STATE_DISCONNECTED) {
                connected = false; handler.post { tvStatus.text = "已断开"; heartView.setBpm(0) }
                handler.postDelayed({ startScan() }, 3000)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val svc = g.getService(HR_SERVICE_UUID) ?: return
            val chr = svc.getCharacteristic(HR_MEASUREMENT_UUID) ?: return
            if (ActivityCompat.checkSelfPermission(this@HeartRateActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            g.setCharacteristicNotification(chr, true)
            chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(it) }
            handler.post { tvStatus.text = "监听心率中" }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == HR_MEASUREMENT_UUID) {
                val f = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                val bpm = c.getIntValue(if (f and 1 != 0) BluetoothGattCharacteristic.FORMAT_UINT16 else BluetoothGattCharacteristic.FORMAT_UINT8, 1)
                currentHeartRate = bpm
                handler.post { heartView.setBpm(bpm) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy(); particleView.stop(); stopScan()
        gatt?.let { if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { it.disconnect(); it.close() } }
        gatt = null; connected = false
    }
}
