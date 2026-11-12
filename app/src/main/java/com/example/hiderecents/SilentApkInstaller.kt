package com.example.hiderecents

import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * 静默安装 APK：文件传输收到 APK 与「APK 安装器」页共用这一份逻辑。
 *
 * 为什么抽出来：两边各写一份时，文件传输那份一直失败并掉到系统安装器，
 * 用户看到的就是「开了自动安装却弹出安装页」。这里统一成三步：
 *   1) cp 到 /data/local/tmp（/storage 对 shell 侧偶发不可读）
 *   2) pm install -r
 *   3) 会话式安装 pm install-create / install-write / install-commit
 * 全程后台执行，不打开任何页面。
 */
object SilentApkInstaller {

    fun install(apkFile: File, ownBinder: IBinder?): Pair<Boolean, String> {
        val binder = TaskHideBridge.resolve(ownBinder)
            ?: return Pair(false, "Shizuku 未就绪")
        if (!apkFile.exists()) return Pair(false, "文件不存在: " + apkFile.absolutePath)

        fun exec(cmd: String): Pair<Boolean, String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(cmd)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val ok = reply.readInt() != 0
                val res = reply.readString() ?: ""
                AppLogger.i("SilentInstall: " + cmd.take(110) + " => ok=" + ok + " out=" + res.take(200))
                Pair(ok, res)
            } catch (t: Throwable) {
                AppLogger.e("SilentInstall failed: " + cmd.take(110) + " :: " + (t.message ?: ""))
                Pair(false, t.message ?: "")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        val size = apkFile.length()
        val tmp = "/data/local/tmp/dsh_silent_" + System.currentTimeMillis() + ".apk"
        val cp = exec("cp '" + apkFile.absolutePath + "' " + tmp)
        val src = if (cp.first) tmp else apkFile.absolutePath

        val direct = exec("pm install -r " + src)
        if (direct.first || direct.second.contains("Success")) {
            exec("rm -f " + tmp)
            return Pair(true, direct.second)
        }

        val create = exec("pm install-create -r -S " + size)
        val open = create.second.indexOf('[')
        val close = if (open >= 0) create.second.indexOf(']', open + 1) else -1
        val sid = if (open >= 0 && close > open) create.second.substring(open + 1, close) else null
        if (sid != null) {
            val w = exec("pm install-write " + sid + " base " + src)
            val commit = exec("pm install-commit " + sid)
            exec("rm -f " + tmp)
            if (commit.first || commit.second.contains("Success")) return Pair(true, commit.second)
            return Pair(false, commit.second.ifBlank { w.second })
        }
        exec("rm -f " + tmp)
        return Pair(false, direct.second.ifBlank { create.second })
    }
}
