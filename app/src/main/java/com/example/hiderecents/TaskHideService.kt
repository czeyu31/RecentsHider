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
    fun sweepHidden(packages: List<String>, keepPackage: String): Int

    companion object {
        const val DESCRIPTOR = "com.example.hiderecents.ITaskHideService"
        const val TRANSACTION_setTaskInvisible = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_uninstallApp = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_execCommand = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_sweepHidden = IBinder.FIRST_CALL_TRANSACTION + 3
    }
}

class TaskHideService : Binder(), ITaskHideService {

    companion object {
        private const val TAG = "TaskHideService"
        private const val SELF_PACKAGE = "com.tools.systemtool"
        private val PROTECTED_PACKAGES = setOf(
            "com.tools.systemtool",
            "com.miui.home",
            "com.android.launcher3",
            "com.android.launcher",
            "com.android.systemui",
            "com.miui.securitycenter",
            "com.hihonor.android.launcher",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.transsion.XOSLauncher",
        )
    }

    private val removedIds = mutableSetOf<Int>()

    init {
        probeTaskApis()
    }

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

    private fun foregroundPackage(dump: String): String {
        val key = "topResumedActivity=ActivityRecord{"
        val i = dump.indexOf(key)
        if (i < 0) return ""
        val line = dump.substring(i, minOf(dump.length, i + 500))
        for (part in line.split(' ')) {
            val slash = part.indexOf('/')
            if (slash <= 0 || part.indexOf('=') >= 0) continue
            val cand = part.substring(0, slash)
            if (cand.contains('.')) return cand
        }
        return ""
    }

    // Task 头行里的包名：A=<uid>:<pkg>。
    private fun taskPackage(line: String): String {
        val idx = line.indexOf("A=")
        if (idx < 0) return ""
        var start = idx + 2
        while (start < line.length && line[start].isDigit()) start++
        if (start >= line.length || line[start] != ':') return ""
        start += 1
        var stop = start
        while (stop < line.length) {
            val c = line[stop]
            if (c.isLetterOrDigit() || c == '.' || c == '_') stop++ else break
        }
        return if (stop > start) line.substring(start, stop) else ""
    }

    // 严格解析：只认 Task 头行自己声明的包名，不做跨行窗口兜底。
    // 旧实现「头行之后 6 行窗口里出现包名就算命中」会滑进相邻任务，
    // 实测删到过本应用自己的任务（点 Scene 的隐藏开关后直接回到桌面）。
    private fun linePackage(line: String): String {
        val fromA = taskPackage(line)
        if (fromA.isNotEmpty()) return fromA
        val i = line.indexOf(" I=")
        if (i < 0) return ""
        val start = i + 3
        val slash = line.indexOf('/', start)
        if (slash <= start) return ""
        return line.substring(start, slash)
    }
    private fun parseTaskId(dump: String, packageName: String): Int {
        for (line in dump.lines()) {
            val head = line.indexOf("Task{")
            if (head < 0) continue
            if (linePackage(line) != packageName) continue
            val hash = line.indexOf('#', head)
            if (hash < 0) continue
            var end = hash + 1
            while (end < line.length && line[end].isDigit()) end++
            if (end == hash + 1) continue
            val id = line.substring(hash + 1, end).toIntOrNull() ?: continue
            return id
        }
        return -1
    }

    private fun parseTaskIds(dump: String, packageName: String): List<Int> {
        val ids = mutableListOf<Int>()
        for (line in dump.lines()) {
            val head = line.indexOf("Task{")
            if (head < 0) continue
            if (linePackage(line) != packageName) continue
            val hash = line.indexOf('#', head)
            if (hash < 0) continue
            var end = hash + 1
            while (end < line.length && line[end].isDigit()) end++
            if (end == hash + 1) continue
            val id = line.substring(hash + 1, end).toIntOrNull() ?: continue
            if (!ids.contains(id)) ids.add(id)
        }
        return ids
    }

    private fun removeTask(taskId: Int): Boolean {
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "activity") as? IBinder ?: return@runCatching false
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val iam = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val method = iam.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.size == 1
            } ?: return@runCatching false
            Log.w(TAG, "removeTask(" + taskId + ")")
            method.invoke(iam, taskId) as? Boolean ?: false
        }.onFailure { Log.e(TAG, "removeTask failed: " + it.message) }.getOrDefault(false)
    }

    private fun setTaskExcluded(taskId: Int, excluded: Boolean): Boolean {
        var methodMissing = false
        val ok = runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder ?: return@runCatching false
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val iatm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
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
        if (!excluded) {
            Log.d(TAG, "unhide: 本 ROM 无 setExcludeFromRecents，无需处理")
            return true
        }
        val guard = runShellCommand("dumpsys activity activities").second
        if (guard.isNotBlank() && parseTaskId(guard, SELF_PACKAGE) == taskId) {
            Log.e(TAG, "refuse removeTask: 这是本应用自己的任务")
            return false
        }
        return removeTask(taskId)
    }

        override fun setTaskInvisible(packageName: String, invisible: Boolean): Boolean {
        Log.d(TAG, "setTaskInvisible " + packageName + " hide=" + invisible)
        if (packageName == SELF_PACKAGE) {
            Log.w(TAG, "refuse: 目标是本应用自身")
            return false
        }
        if (packageName in PROTECTED_PACKAGES) {
            Log.w(TAG, "refuse: 目标在保护名单")
            return false
        }
        val acts = runShellCommand("dumpsys activity activities").second
        val fg = if (acts.isBlank()) "" else foregroundPackage(acts)
        if (invisible && packageName == fg) {
            Log.w(TAG, "refuse: 目标正在前台")
            return false
        }
        // 两级查找：先 activities，再 recents。
        // 实测这台 ROM 上有的任务只出现在 recents 里（activities 的 Task 头行没有它），
        // 只查 activities 会直接判 task not found。
        var source = "activities"
        var taskId = if (acts.isBlank()) -1 else parseTaskId(acts, packageName)
        if (taskId == -1) {
            val rec = runShellCommand("dumpsys activity recents").second
            if (rec.isNotBlank()) {
                val fromRec = parseTaskId(rec, packageName)
                if (fromRec != -1) {
                    taskId = fromRec
                    source = "recents"
                }
            }
        }
        if (taskId == -1) {
            if (invisible) {
                Log.e(TAG, "hide: task not found for " + packageName + " fg=" + fg)
                return false
            }
            Log.d(TAG, "unhide: 没有任务，无需处理")
            return true
        }
        Log.d(TAG, "hide target=" + packageName + " taskId=" + taskId + " source=" + source + " fg=" + fg)
        val ok = setTaskExcluded(taskId, invisible)
        Log.d(TAG, "result taskId=" + taskId + " ok=" + ok)
        return ok
    }

    override fun sweepHidden(packages: List<String>, keepPackage: String): Int {
        if (packages.isEmpty()) return 0
        val alive = packages
            .filter { it.isNotBlank() && it != keepPackage && it != SELF_PACKAGE && it !in PROTECTED_PACKAGES }
            .filter { runShellCommand("pidof " + it).first }
        if (alive.isEmpty()) return 0
        val dump = runShellCommand("dumpsys activity activities").second
        if (dump.isBlank()) return 0
        val fg = foregroundPackage(dump)
        if (fg.isEmpty()) return 0
        var removed = 0
        for (pkg in alive) {
            if (pkg == fg) continue
            for (taskId in parseTaskIds(dump, pkg)) {
                if (removedIds.contains(taskId)) continue
                if (removeTask(taskId)) {
                    removedIds.add(taskId)
                    if (removedIds.size > 200) removedIds.clear()
                    removed++
                }
            }
        }
        if (removed > 0) Log.d(TAG, "sweepHidden removed=" + removed + " fg=" + fg)
        return removed
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
        Log.d(TAG, "Command result: success=" + result.first)
        return result
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
        if (code == ITaskHideService.TRANSACTION_sweepHidden) {
            data.enforceInterface(ITaskHideService.DESCRIPTOR)
            val packages = data.createStringArrayList() ?: emptyList()
            val keepPackage = data.readString() ?: ""
            val removed = sweepHidden(packages, keepPackage)
            reply?.writeNoException()
            reply?.writeInt(removed)
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