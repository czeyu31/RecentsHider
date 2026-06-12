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

    private fun runShellCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode == 0, output)
        } catch (e: Exception) {
            Log.e(TAG, "Shell error: ${e.message}")
            Pair(false, "")
        }
    }

    override fun setTaskInvisible(packageName: String, invisible: Boolean): Boolean {
        Log.d(TAG, "=== $packageName hide=$invisible ===")

        if (!invisible) {
            // Restore: relaunch the app using am start
            Log.d(TAG, "Relaunching $packageName")
            val (_, resolveOutput) = runShellCommand("cmd package resolve-activity --brief $packageName 2>/dev/null | tail -1")
            val component = resolveOutput.trim()
            if (component.contains("/")) {
                runShellCommand("am start -n $component")
            } else {
                runShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName")
            }
            return true
        }

        // Hide: find task ID and remove it
        val (_, dump) = runShellCommand("dumpsys activity recents")
        val taskId = parseTaskId(dump, packageName)

        if (taskId == -1) {
            Log.e(TAG, "Task not found for: $packageName")
            return false
        }

        Log.d(TAG, "Found taskId=$taskId for $packageName")

        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val iam = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

            val removeTaskMethod = iam.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.size == 1
            }

            if (removeTaskMethod != null) {
                val result = removeTaskMethod.invoke(iam, taskId) as? Boolean ?: false
                Log.d(TAG, "removeTask($taskId) result=$result")
                result
            } else {
                Log.e(TAG, "removeTask method not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            false
        }
    }

    override fun uninstallApp(packageName: String, deleteData: Boolean): Boolean {
        Log.d(TAG, "Uninstalling $packageName, deleteData=$deleteData")
        val command = if (deleteData) {
            "pm uninstall $packageName"
        } else {
            "pm uninstall --user 0 $packageName"
        }
        val (success, output) = runShellCommand(command)
        Log.d(TAG, "Uninstall result: success=$success, output=$output")
        return success
    }

    override fun execCommand(command: String): Pair<Boolean, String> {
        Log.d(TAG, "Executing command: $command")
        val result = runShellCommand(command)
        Log.d(TAG, "Command result: success=${result.first}, output=${result.second}")
        return result
    }

    private fun parseTaskId(dump: String, packageName: String): Int {
        val lines = dump.lines()
        for (line in lines) {
            if (line.contains(packageName) && line.contains("Task{")) {
                val match = Regex("Task\\{[a-f0-9]+ #(\\d+)").find(line)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull() ?: -1
                }
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
