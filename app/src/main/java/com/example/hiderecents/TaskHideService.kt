package com.example.hiderecents

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log

interface ITaskHideService : IInterface {
    fun setTaskInvisible(packageName: String, invisible: Boolean): Boolean
    fun uninstallApp(packageName: String, deleteData: Boolean): Boolean
    fun execCommand(command: String): Pair<Boolean, String>
    companion object {
        const val DESCRIPTOR = "com.example.hiderecents.ITaskHideService"
        const val TRANSACTION_setTaskInvisible = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_uninstallApp = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_execCommand = IBinder.FIRST_CALL_TRANSACTION + 2
    }
}

class TaskHideService : Binder(), ITaskHideService {

    companion object {
        private const val TAG = "TaskHideService"
    }

    init {
        probeTaskApis()
    }

    /**
     * 诊断：枚举 activity_task / activity 两个服务上与「任务可见性」相关的方法名。
     *
     * 背景（实测）：
     *   · IActivityTaskManager 共 146 个方法，与任务可见性相关的只有
     *     removeTask / removeAllVisibleRecentTasks / getRecentTasks /
     *     startActivityFromRecents / startRecentsActivity / cancelRecentsAnimation，
     *     没有 setExcludeFromRecents。
     *   · 于是隐藏只能退回 removeTask，它销毁的是「任务」（UI 状态）而不是「进程」，
     *     用户看到的就是「歌一直在播、但每次打开都从开屏动画冷启动」。
     *
     * 这一步只确认 MIUI 有没有把该能力放到旧的 activity 服务上，不改变任何行为。
     */
    private fun probeTaskApis() {
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            for (svc in listOf("activity_task", "activity")) {
                val binder = getService.invoke(null, svc) as? IBinder
                if (binder == null) {
                    Log.w(TAG, "probe[" + svc + "] binder is null")
                    continue
                }
                val iface = if (svc == "activity_task") {
                    val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
                    stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                } else {
                    val stub = Class.forName("android.app.IActivityManager\$Stub")
                    stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                }
                val names = iface.javaClass.methods.map { it.name }.distinct()
                val hits = names.filter { n ->
                    n.contains("xclude", true) || n.contains("ecent", true) || n.contains("emoveTask", true)
                }.sorted()
                Log.d(TAG, "probe[" + svc + "] total=" + names.size + " candidates=" + hits.joinToString(","))
            }
        }.onFailure { Log.w(TAG, "probe failed", it) }
    }

    private fun runShellCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode == 0, output)
        } catch (e: Exception) {
            Log.e(TAG, "Shell error: " + e.message)
            Pair(false, "")
        }
    }

    /**
     * 在「全部任务栈」里找任务 id。
     *
     * 不能用 dumpsys activity recents 作为唯一来源：任务一旦被排除，就从 recents 里消失，
     * 再拿 recents 去查永远得到 -1。所以以 dumpsys activity activities（完整任务栈）为主，
     * recents 只作兜底。
     */
    private fun findTaskId(packageName: String): Int {
        val (_, acts) = runShellCommand("dumpsys activity activities")
        val fromActs = parseTaskId(acts, packageName)
        if (fromActs != -1) return fromActs
        val (_, rec) = runShellCommand("dumpsys activity recents")
        return parseTaskId(rec, packageName)
    }

    /**
     * 只把任务标记为「不出现在最近任务里」，不结束它。
     *
     * 优先 IActivityTaskManager.setExcludeFromRecents(taskId, excluded)。
     * 实测这台 ROM 没有该方法（146 个方法里不存在），于是回退 removeTask ——
     * 后者会销毁任务本身，但不会杀进程（进程靠前台服务继续跑）。
     * 回退时明确记日志，避免以后把「任务被销毁」误判成「应用被杀」。
     */
    private fun setTaskExcluded(taskId: Int, excluded: Boolean): Boolean {
        var methodMissing = false
        val ok = runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder
                ?: return@runCatching false
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val iatm = stubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val method = iatm.javaClass.methods.firstOrNull {
                it.name == "setExcludeFromRecents" && it.parameterTypes.size == 2
            }
            if (method == null) {
                methodMissing = true
                Log.e(TAG, "setExcludeFromRecents not found on this ROM")
                false
            } else {
                method.invoke(iatm, taskId, excluded)
                Log.d(TAG, "setExcludeFromRecents(" + taskId + ", " + excluded + ") ok")
                true
            }
        }.onFailure { Log.e(TAG, "setExcludeFromRecents failed: " + it.message) }.getOrDefault(false)

        if (ok) return true
        if (!methodMissing) return false

        // 关键修复（v396）：只有「隐藏」才允许回退到 removeTask。
        //
        // v395 实测暴露的 bug：用户点开关「取消隐藏」时走的是 excluded=false，
        // 但因为本 ROM 没有 setExcludeFromRecents，代码同样掉进这个回退分支，
        // 于是「恢复」把任务 removeTask 掉了 —— 想恢复反而被销毁，比原问题更糟。
        //
        // 语义上也讲不通：本 ROM 既然没有该标记能力，任务就不可能被打过标记，
        // 取消隐藏本就没有任何东西需要清除。所以这里直接返回成功，不碰任务。
        if (!excluded) {
            Log.d(TAG, "unhide: 本 ROM 无 setExcludeFromRecents，任务从未被标记，无需处理")
            return true
        }

        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val iam = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val removeTask = iam.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.size == 1
            } ?: return@runCatching false
            Log.w(TAG, "fallback removeTask(" + taskId + ") —— 该调用销毁任务（进程通常仍存活）")
            removeTask.invoke(iam, taskId) as? Boolean ?: false
        }.onFailure { Log.e(TAG, "fallback removeTask failed: " + it.message) }.getOrDefault(false)
    }

    /**
     * 隐藏 / 取消隐藏。
     *
     * 隐藏：要求该应用当前有任务。
     * 取消隐藏：任务已被销毁时无法清除标记，视为已完成（标记随任务消失）；
     * 不再用 am start 强拉应用 —— 那会打断用户正在做的事。
     */
    override fun setTaskInvisible(packageName: String, invisible: Boolean): Boolean {
        Log.d(TAG, "=== " + packageName + " hide=" + invisible + " ===")
        val taskId = findTaskId(packageName)

        if (invisible) {
            if (taskId == -1) {
                Log.e(TAG, "hide: task not found for " + packageName)
                return false
            }
            Log.d(TAG, "hide: taskId=" + taskId)
            return setTaskExcluded(taskId, true)
        }

        if (taskId == -1) {
            Log.d(TAG, "unhide: no task for " + packageName + "（任务已不存在），无需清除标记")
            return true
        }
        Log.d(TAG, "unhide: taskId=" + taskId)
        return setTaskExcluded(taskId, false)
    }

    override fun uninstallApp(packageName: String, deleteData: Boolean): Boolean {
        Log.d(TAG, "Uninstalling " + packageName + ", deleteData=" + deleteData)
        val command = if (deleteData) {
            "pm uninstall " + packageName
        } else {
            "pm uninstall --user 0 " + packageName
        }
        val (success, output) = runShellCommand(command)
        Log.d(TAG, "Uninstall result: success=" + success + ", output=" + output)
        return success
    }

    override fun execCommand(command: String): Pair<Boolean, String> {
        Log.d(TAG, "Executing command: " + command)
        val result = runShellCommand(command)
        Log.d(TAG, "Command result: success=" + result.first + ", output=" + result.second)
        return result
    }

    /**
     * 从 dump 文本里解析某个包的任务 id。
     *
     * 主路径：任务行本身带包名。
     * 兜底：包名出现在任务行之后若干行（部分 ROM 的分段输出）。
     */
    private fun parseTaskId(dump: String, packageName: String): Int {
        val escaped = Regex.escape(packageName)
        val oneline = Regex("Task\\{[a-f0-9]+ #(\\d+)[^}]*" + escaped).find(dump)
        if (oneline != null) return oneline.groupValues[1].toIntOrNull() ?: -1

        val lines = dump.lines()
        val head = Regex("Task\\{[a-f0-9]+ #(\\d+)")
        for (i in lines.indices) {
            val m = head.find(lines[i]) ?: continue
            val window = lines.subList(i, minOf(i + 6, lines.size)).joinToString(" ")
            if (window.contains(packageName)) {
                return m.groupValues[1].toIntOrNull() ?: -1
            }
        }
        return -1
    }

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ITaskHideService.TRANSACTION_setTaskInvisible) {
            data.enforceInterface(ITaskHideService.DESCRIPTOR)
            val packageName = data.readString() ?: ""
            val invisible = data.readByte().toInt() != 0
            val result = setTaskInvisible(packageName, invisible)
            reply?.writeNoException()
            reply?.writeInt(if (result) 1 else 0)
            return true
        }
        if (code == ITaskHideService.TRANSACTION_uninstallApp) {
            data.enforceInterface(ITaskHideService.DESCRIPTOR)
            val packageName = data.readString() ?: ""
            val deleteData = data.readByte().toInt() != 0
            val result = uninstallApp(packageName, deleteData)
            reply?.writeNoException()
            reply?.writeInt(if (result) 1 else 0)
            return true
        }
        if (code == ITaskHideService.TRANSACTION_execCommand) {
            data.enforceInterface(ITaskHideService.DESCRIPTOR)
            val command = data.readString() ?: ""
            val (success, output) = execCommand(command)
            reply?.writeNoException()
            reply?.writeInt(if (success) 1 else 0)
            reply?.writeString(output)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}
