package com.example.hiderecents.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** 心率服务与测量特征的标准 UUID。 */
val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** 扫描到的一台设备。hrAdvertising 表示广播包里带标准心率服务 0x180D。 */
data class HrDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val hrAdvertising: Boolean = false,
)

/** 连接状态。READY 表示已经订上 0x2A37，可以收到心率。 */
enum class HrLink { IDLE, SCANNING, CONNECTING, READY, LOST, FAILED }

/**
 * 心率广播通道。
 *
 * 流程：扫 0x180D（心率服务）→ 连接 GATT → 发现服务 → 打开 0x2A37 的 notify。
 * 连接不是系统「配对」：不弹配对框，设备也不会进已配对列表。
 *
 * 广播包本身通常不带心率值（只有少数设备带），所以必须先连上才有数。
 * 所有回调都在 binder 线程上抛回来，调用方自己切到主线程。
 */
@SuppressLint("MissingPermission")
class HeartRateBle(private val context: Context) {

    private companion object {
        /** GATT_ERROR，小米等机型上连接失败最常见的错误码。 */
        const val ERROR_GATT = 133
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 600L
        /** 设备不在附近时系统会把「连接中」一直挂着，超过这个时间就当失败。 */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /** 133 重试用：上一次要连的设备、已经重试了几次。 */
    private var lastTarget: HrDevice? = null
    private var retries = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryTick = Runnable {
        val t = lastTarget
        val a = adapter
        if (t != null && a != null && a.isEnabled) connectNow(a, t)
    }

    /** 连接超时：连上了但迟迟读不到心率服务，也让 UI 从「连接中」里出来。 */
    private var ready = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutTick = Runnable {
        if (!ready) {
            close()
            onLink?.invoke(HrLink.FAILED, "连接超时，把设备靠近手机再试一次")
        }
    }

    /** 连接状态下的信号强度刷新间隔。 */
    private val RSSI_INTERVAL_MS = 4000L

    private val found = LinkedHashMap<String, HrDevice>()

    /** 扫描到/更新设备时回调，参数是按信号强度排好序的完整列表。 */
    var onDevices: ((List<HrDevice>) -> Unit)? = null
    /** 收到一次心率值。 */
    var onBpm: ((Int) -> Unit)? = null
    /** 连接状态变化。 */
    var onLink: ((HrLink, String) -> Unit)? = null
    /** 连接后读到的信号强度。 */
    var onRssi: ((Int) -> Unit)? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ------------------------------------------------------------------ 扫描

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onLink?.invoke(HrLink.FAILED, "蓝牙未打开")
            return
        }
        if (scanning) return
        val s = a.bluetoothLeScanner ?: run {
            onLink?.invoke(HrLink.FAILED, "本机不支持蓝牙低功耗扫描")
            return
        }
        found.clear()
        onDevices?.invoke(emptyList())
        scanner = s
        scanning = true
        onLink?.invoke(HrLink.SCANNING, "正在扫描心率设备")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            scanning = false
            onLink?.invoke(HrLink.FAILED, "扫描失败：" + (e.message ?: "未知原因"))
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val record = result.scanRecord
            val hasHr = record?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
            val rawName = record?.deviceName?.trim().orEmpty()
                .ifEmpty { dev.name?.trim().orEmpty() }
            // 没有名字也没有心率服务的，列出来只是噪音
            if (rawName.isEmpty() && !hasHr) return
            val address = dev.address ?: return
            found[address] = HrDevice(
                address = address,
                name = rawName.ifEmpty { "未命名设备" },
                rssi = result.rssi,
                hrAdvertising = hasHr,
            )
            onDevices?.invoke(
                found.values.sortedWith(
                    compareByDescending<HrDevice> { it.hrAdvertising }.thenByDescending { it.rssi },
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onLink?.invoke(HrLink.FAILED, "扫描失败（错误码 $errorCode）")
        }
    }

    // ------------------------------------------------------------------ 连接

    fun connect(target: HrDevice) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onLink?.invoke(HrLink.FAILED, "蓝牙未打开")
            return
        }
        stopScan()
        close()
        retries = 0
        lastTarget = target
        ready = false
        timeoutHandler.removeCallbacks(timeoutTick)
        timeoutHandler.postDelayed(timeoutTick, CONNECT_TIMEOUT_MS)
        connectNow(a, target)
    }

    private fun connectNow(a: BluetoothAdapter, target: HrDevice) {
        val device: BluetoothDevice = try {
            a.getRemoteDevice(target.address)
        } catch (e: Exception) {
            onLink?.invoke(HrLink.FAILED, "找不到这台设备")
            return
        }
        onLink?.invoke(HrLink.CONNECTING, "正在连接 " + target.name)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        stopRssiPolling()
        timeoutHandler.removeCallbacks(timeoutTick)
        ready = false
        close()
        onLink?.invoke(HrLink.IDLE, "已断开")
    }

    // ------------------------------------------------------------------ 信号强度

    /**
     * 连上之后扫描就停了，RSSI 不会自己刷新，只能主动 readRemoteRssi。
     * 每 4 秒读一次，够看出「拿远一点就掉一格」的变化，又不至于太费电。
     */
    private val rssiHandler = Handler(Looper.getMainLooper())
    private val rssiTick = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            try {
                g.readRemoteRssi()
            } catch (_: Exception) {
            }
            rssiHandler.postDelayed(this, RSSI_INTERVAL_MS)
        }
    }

    private fun startRssiPolling() {
        rssiHandler.removeCallbacks(rssiTick)
        rssiHandler.postDelayed(rssiTick, RSSI_INTERVAL_MS)
    }

    private fun stopRssiPolling() {
        rssiHandler.removeCallbacks(rssiTick)
    }

    private fun close() {
        stopRssiPolling()
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                retries = 0
                onLink?.invoke(HrLink.CONNECTING, "已连接，正在读取服务")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                timeoutHandler.removeCallbacks(timeoutTick)
                close()
                // 133（GATT_ERROR）在小米/部分 ROM 上是常态：先自己重连两次，两次都不行再交给用户
                if (status == ERROR_GATT && retries < MAX_RETRIES && lastTarget != null) {
                    retries += 1
                    onLink?.invoke(HrLink.CONNECTING, "连接不稳定，正在重试（" + retries + "/" + MAX_RETRIES + "）")
                    retryHandler.removeCallbacks(retryTick)
                    retryHandler.postDelayed(retryTick, RETRY_DELAY_MS)
                    return
                }
                val text = if (status == BluetoothGatt.GATT_SUCCESS) "设备已断开" else "连接失败（$status）"
                onLink?.invoke(HrLink.LOST, text)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                close()
                if (status == ERROR_GATT && retries < MAX_RETRIES && lastTarget != null) {
                    retries += 1
                    onLink?.invoke(HrLink.CONNECTING, "连接不稳定，正在重试（" + retries + "/" + MAX_RETRIES + "）")
                    retryHandler.removeCallbacks(retryTick)
                    retryHandler.postDelayed(retryTick, RETRY_DELAY_MS)
                    return
                }
                onLink?.invoke(HrLink.FAILED, "连接失败（$status）")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLink?.invoke(HrLink.FAILED, "读取服务失败（$status）")
                return
            }
            val service = g.getService(HR_SERVICE)
            val ch = service?.getCharacteristic(HR_MEASUREMENT)
            if (service == null || ch == null) {
                onLink?.invoke(HrLink.FAILED, "这台设备没有标准心率服务（0x180D）")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) {
                ready = true
                timeoutHandler.removeCallbacks(timeoutTick)
                onLink?.invoke(HrLink.READY, "已连接（未找到订阅描述符）")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            ready = true
            timeoutHandler.removeCallbacks(timeoutTick)
            onLink?.invoke(HrLink.READY, "已连接，等待心率数据")
            startRssiPolling()
            g.readRemoteRssi()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLink?.invoke(HrLink.FAILED, "订阅心率失败（$status）")
            }
        }

        /** Android 13+ 走这个重载。 */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HR_MEASUREMENT) emit(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HR_MEASUREMENT) emit(characteristic.value ?: return)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onRssi?.invoke(rssi)
        }
    }

    /**
     * 心率测量数据：首字节是标志位，bit0 = 1 表示心率是 uint16，否则 uint8。
     * 后面的字段（能量消耗、RR 间期）这里不需要。
     */
    private fun emit(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (data.size < 3) return
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            if (data.size < 2) return
            data[1].toInt() and 0xFF
        }
        if (bpm in 20..250) onBpm?.invoke(bpm)
    }
}
