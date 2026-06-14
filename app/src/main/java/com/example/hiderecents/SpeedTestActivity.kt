package com.example.hiderecents

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class SpeedTestActivity : AppCompatActivity() {

    private lateinit var tvPing: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var pbUpload: ProgressBar
    private lateinit var gaugeDownload: GaugeView
    private lateinit var gaugeUpload: GaugeView
    private lateinit var etServerUrl: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var testing = false

    private val cloudflareBase = "https://speed.cloudflare.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speed_test)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        tvPing = findViewById(R.id.tvPing)
        tvJitter = findViewById(R.id.tvJitter)
        tvDownload = findViewById(R.id.tvDownload)
        tvUpload = findViewById(R.id.tvUpload)
        tvStatus = findViewById(R.id.tvStatus)
        tvIp = findViewById(R.id.tvIp)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        pbDownload = findViewById(R.id.pbDownload)
        pbUpload = findViewById(R.id.pbUpload)
        gaugeDownload = findViewById(R.id.gaugeDownload)
        gaugeUpload = findViewById(R.id.gaugeUpload)
        etServerUrl = findViewById(R.id.etServerUrl)

        gaugeDownload.setAccentColor(getColor(R.color.primary))
        gaugeUpload.setAccentColor(getColor(R.color.secondary))

        // Set default server
        etServerUrl.setText(cloudflareBase)
        tvServerStatus.text = "默认: Cloudflare 测速节点"

        // Save custom server
        findViewById<Button>(R.id.btnSaveServer).setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                val prefs = getSharedPreferences("speed_test", Context.MODE_PRIVATE)
                prefs.edit().putString("server_url", url).apply()
                tvServerStatus.text = "已保存: $url"
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!testing) startTest()
        }

        // Get IP
        Thread {
            try {
                val conn = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val output = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val ipLine = output.lines().firstOrNull { it.startsWith("ip=") }
                val ip = ipLine?.substringAfter("ip=") ?: "未知"
                runOnUiThread {
                    tvIp.text = "IP: $ip"
                    tvServerStatus.text = "Cloudflare 已连接"
                    tvServerStatus.setTextColor(getColor(R.color.primary))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvIp.text = "IP 获取失败"
                    tvServerStatus.text = "连接失败: ${e.message}"
                    tvServerStatus.setTextColor(getColor(R.color.error))
                }
            }
        }.start()
    }

    private fun startTest() {
        testing = true
        handler.post {
            tvStatus.text = "测试中..."
            pbDownload.progress = 0
            pbUpload.progress = 0
        }

        Thread {
            // Phase 1: Ping test
            handler.post { tvStatus.text = "测量延迟..." }
            val pings = mutableListOf<Long>()
            for (i in 1..5) {
                try {
                    val start = System.currentTimeMillis()
                    val conn = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.inputStream.close()
                    conn.disconnect()
                    pings.add(System.currentTimeMillis() - start)
                } catch (_: Exception) {}
            }

            if (pings.isNotEmpty()) {
                val avgPing = pings.average()
                val jitter = if (pings.size > 1) {
                    var sum = 0.0
                    for (i in 1 until pings.size) sum += Math.abs(pings[i].toDouble() - pings[i - 1].toDouble())
                    sum / (pings.size - 1)
                } else 0.0
                handler.post {
                    tvPing.text = String.format("%.0f", avgPing)
                    tvJitter.text = String.format("%.1f", jitter)
                }
            }

            // Phase 2: Download test
            handler.post { tvStatus.text = "下载测试中..." }
            val sizes = intArrayOf(1, 5, 10, 25, 50) // MB
            var bestDlSpeed = 0.0

            for (sizeMB in sizes) {
                if (!testing) break
                try {
                    val url = URL("$cloudflareBase/__down?bytes=${sizeMB * 1000000}")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 15000

                    val startTime = System.currentTimeMillis()
                    val input = conn.inputStream
                    val buffer = ByteArray(32768)
                    var totalRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalRead += bytesRead
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        if (elapsed > 0) {
                            val speedMbps = (totalRead * 8.0 / 1000000.0) / elapsed
                            handler.post {
                                tvDownload.text = String.format("%.2f", speedMbps)
                                gaugeDownload.setProgress(speedMbps.toFloat(), 200f)
                                pbDownload.progress = ((elapsed / 5.0) * 100).toInt().coerceAtMost(100)
                            }
                        }
                        if (System.currentTimeMillis() - startTime > 5000) break
                    }

                    input.close()
                    conn.disconnect()

                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    if (elapsed > 1.0) {
                        val speedMbps = (totalRead * 8.0 / 1000000.0) / elapsed
                        if (speedMbps > bestDlSpeed) bestDlSpeed = speedMbps
                    }
                } catch (_: Exception) {}
            }

            handler.post {
                tvDownload.text = String.format("%.2f", bestDlSpeed)
                gaugeDownload.setProgress(bestDlSpeed.toFloat(), 200f)
                pbDownload.progress = 100
            }

            // Phase 3: Upload test
            handler.post { tvStatus.text = "上传测试中..." }
            var bestUlSpeed = 0.0
            val uploadSizes = intArrayOf(1, 5, 10) // MB

            for (sizeMB in uploadSizes) {
                if (!testing) break
                try {
                    val data = ByteArray(sizeMB * 1000000)
                    java.util.Random().nextBytes(data)

                    val startTime = System.currentTimeMillis()
                    val conn = URL("$cloudflareBase/__up").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 15000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/octet-stream")

                    val output = conn.outputStream
                    var sent = 0
                    val chunkSize = 32768
                    while (sent < data.size) {
                        val toSend = minOf(chunkSize, data.size - sent)
                        output.write(data, sent, toSend)
                        sent += toSend
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        if (elapsed > 0) {
                            val speedMbps = (sent * 8.0 / 1000000.0) / elapsed
                            handler.post {
                                tvUpload.text = String.format("%.2f", speedMbps)
                                gaugeUpload.setProgress(speedMbps.toFloat(), 100f)
                                pbUpload.progress = ((elapsed / 5.0) * 100).toInt().coerceAtMost(100)
                            }
                        }
                        if (System.currentTimeMillis() - startTime > 5000) break
                    }
                    output.flush()
                    output.close()
                    conn.responseCode

                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    if (elapsed > 1.0) {
                        val speedMbps = (sent * 8.0 / 1000000.0) / elapsed
                        if (speedMbps > bestUlSpeed) bestUlSpeed = speedMbps
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }

            handler.post {
                tvUpload.text = String.format("%.2f", bestUlSpeed)
                gaugeUpload.setProgress(bestUlSpeed.toFloat(), 100f)
                pbUpload.progress = 100
                tvStatus.text = "测试完成"
                testing = false
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        testing = false
    }
}
