package com.example.hiderecents.rc

import android.content.Context
import android.util.Log
import ernest.ascrcpy.adb.AdbChannel
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient

/**
 * ADB host 授权通道（beta 专用）。
 *
 * 这是除 Shizuku 之外的第二条 shell 来源：控制端自己实现 adb 客户端，
 * 通过被控端的「无线调试」配对一次拿到授权，之后就能以 shell 身份
 * push/启动 scrcpy 服务端，并直接打开 localabstract 通道。
 *
 * 与 Shizuku 通道并存、互不影响：Shizuku 可用时走 Shizuku，不行再走这里。
 */
object AdbBridge {

    private const val TAG = "AdbBridge"

    private var client: AdbClient? = null

    private fun client(context: Context): AdbClient =
        client ?: DefaultAdbClient.factory(context).create().also { client = it }

    /** 配对：被控端「无线调试 → 使用配对码配对设备」里显示的端口与六位码。 */
    suspend fun pair(context: Context, host: String, port: Int, code: String): String {
        return runCatching {
            client(context).pairWireless(AdbEndpoint(host, port), code)
        }.onFailure { Log.w(TAG, "pair failed", it) }
            .getOrElse { throw it }
    }

    /** 连接：配对之后用「无线调试」主端口（不是配对端口）建立会话。 */
    suspend fun connect(context: Context, host: String, port: Int): String {
        return runCatching {
            client(context).connectWireless(AdbEndpoint(host, port)).banner
        }.onFailure { Log.w(TAG, "connect failed", it) }
            .getOrElse { throw it }
    }

    suspend fun shell(context: Context, command: String): String {
        return runCatching { client(context).shell(command).text() }
            .getOrElse { "ERR: " + (it.message ?: "") }
    }

    suspend fun pushAsset(context: Context, assetName: String, remotePath: String): Boolean {
        return runCatching {
            context.assets.open(assetName).use { input ->
                client(context).push(input, remotePath)
            }
            Log.i(TAG, "pushed $assetName -> $remotePath")
            true
        }.onFailure { Log.w(TAG, "push failed", it) }.getOrDefault(false)
    }

    suspend fun openChannel(context: Context, name: String): AdbChannel? =
        runCatching { client(context).open(name) }
            .onFailure { Log.w(TAG, "open failed: $name", it) }
            .getOrNull()

    fun close() {
        runCatching { client?.close() }
        client = null
    }
}