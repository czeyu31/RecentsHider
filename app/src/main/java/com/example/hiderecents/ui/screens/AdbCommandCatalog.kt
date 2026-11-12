package com.example.hiderecents.ui.screens

/**
 * 常用 ADB / shell 命令目录。
 *
 * 每条命令都带中文说明，按分组展示在「常用命令」面板里；
 * dangerous = true 的命令会造成破坏（卸载、删目录、重启、改 SELinux 等），列表里用朱红强调色标出。
 */
data class AdbCommandItem(
    val command: String,
    val description: String,
    val group: String,
    val dangerous: Boolean = false,
)

private const val G_APP = "应用 & 进程管理"
private const val G_FILE = "文件 & 目录操作"
private const val G_SYS = "系统信息 & 磁盘"
private const val G_SHOW = "显示 & 系统设置"
private const val G_DEBUG = "调试 & 日志"
private const val G_SEC = "权限 & 安全"
private const val G_NET = "网络"
private const val G_POWER = "重启 & 关机"
private const val G_MOUNT = "挂载 & 磁盘修复"
private const val G_UTIL = "实用小工具"

val AdbCommandCatalog: List<AdbCommandItem> = listOf(
    AdbCommandItem("am start -n <package>/<activity>", "启动指定应用的指定 Activity 组件", G_APP),
    AdbCommandItem("am force-stop <package>", "强制停止指定的应用包，终止其所有进程", G_APP),
    AdbCommandItem("am kill <package>", "终止指定应用包的后台进程", G_APP),
    AdbCommandItem("am kill-all", "终止所有后台进程", G_APP),
    AdbCommandItem("am broadcast -a <action>", "发送指定的系统广播", G_APP),
    AdbCommandItem("pm install <apk_path>", "安装指定路径下的 APK 文件", G_APP),
    AdbCommandItem("pm uninstall <package>", "卸载指定的应用包，同时删除其数据", G_APP, dangerous = true),
    AdbCommandItem("pm uninstall -k <package>", "卸载指定的应用包并保留其数据", G_APP, dangerous = true),
    AdbCommandItem("pm clear <package>", "清除指定应用包的数据和缓存，应用回到初始状态", G_APP, dangerous = true),
    AdbCommandItem("pm disable <package/component>", "禁用指定应用包的某个组件", G_APP, dangerous = true),
    AdbCommandItem("pm enable <package/component>", "启用指定应用包的某个组件", G_APP),
    AdbCommandItem("pm grant <package> <Permission>", "为指定应用包授予指定的运行时权限", G_APP),
    AdbCommandItem("pm revoke <package> <Permission>", "撤销指定应用包的指定权限", G_APP),
    AdbCommandItem("pm list packages", "列出设备上所有已安装的应用包名", G_APP),
    AdbCommandItem("pm list packages -s", "仅列出系统应用包名", G_APP),
    AdbCommandItem("pm list packages -3", "仅列出第三方应用包名", G_APP),
    AdbCommandItem("pm list permissions", "列出设备上定义的所有权限", G_APP),
    AdbCommandItem("ps", "显示正在运行的进程信息", G_APP),
    AdbCommandItem("ps -ef", "以全格式显示所有进程信息（含进程路径、启动参数）", G_APP),
    AdbCommandItem("top", "实时显示正在运行的进程状态", G_APP),
    AdbCommandItem("top -m <n>", "仅显示 CPU 占用率最高的 N 个进程", G_APP),
    AdbCommandItem("kill <pid>", "终止指定进程 ID（PID）的进程", G_APP),
    AdbCommandItem("kill -9 <pid>", "强制终止指定进程 ID 的进程，无法被忽略", G_APP),
    AdbCommandItem("lsof", "列出当前系统中打开的文件及对应的进程", G_APP),

    AdbCommandItem("pwd", "显示当前的工作目录路径", G_FILE),
    AdbCommandItem("cd <目录> && <命令>", "进入目录后执行命令；本页每条命令都是新 shell，cd 不会保留到下一次", G_FILE),
    AdbCommandItem("ls", "列出指定路径下的文件和目录", G_FILE),
    AdbCommandItem("ls -l", "以长格式列出文件与目录（权限、所有者、大小、修改时间）", G_FILE),
    AdbCommandItem("ls -R", "递归列出所有文件和目录（含子目录）", G_FILE),
    AdbCommandItem("ls -s", "列出文件和目录并显示其大小", G_FILE),
    AdbCommandItem("mkdir <file_path>", "创建一个新的目录", G_FILE),
    AdbCommandItem("touch <file_path>", "创建新的空文件，或更新文件的修改时间戳", G_FILE),
    AdbCommandItem("cat <file_path>", "显示指定文件的内容", G_FILE),
    AdbCommandItem("cp <from> <to>", "将文件或目录从一个位置复制到另一个位置", G_FILE),
    AdbCommandItem("cp -r <from> <to>", "递归复制目录及其全部内容", G_FILE),
    AdbCommandItem("mv <from> <to>", "移动或重命名文件、目录", G_FILE),
    AdbCommandItem("rm <file_path>", "删除指定的文件", G_FILE, dangerous = true),
    AdbCommandItem("rm -rf <directory_path>", "强制递归删除目录及其所有内容（慎用）", G_FILE, dangerous = true),
    AdbCommandItem("rmdir <directory_path>", "删除空目录", G_FILE),
    AdbCommandItem("chmod <mode> <file_path>", "修改文件或目录的权限，例如 chmod 777 file.txt", G_FILE),
    AdbCommandItem("chown <user:group> <file_path>", "修改文件或目录的所有者和所属组", G_FILE),
    AdbCommandItem("find <path> -name <filename>", "在指定路径下按名称查找文件或目录", G_FILE),
    AdbCommandItem("file <file_path>", "判断指定文件的类型", G_FILE),
    AdbCommandItem("stat <file_path>", "显示指定文件的详细状态信息", G_FILE),

    AdbCommandItem("getprop", "列出本机全部系统属性（品牌、型号、版本等）", G_SYS),
    AdbCommandItem("getprop ro.product.model", "查看设备型号", G_SYS),
    AdbCommandItem("getprop ro.build.version.release", "查看 Android 系统版本号", G_SYS),
    AdbCommandItem("getprop ro.build.version.sdk", "查看 Android API 级别（SDK 版本）", G_SYS),
    AdbCommandItem("getprop | grep <keyword>", "按关键词过滤系统属性", G_SYS),
    AdbCommandItem("uname -a", "显示系统的详细信息（内核、架构等）", G_SYS),
    AdbCommandItem("whoami", "显示当前登录的用户名称", G_SYS),
    AdbCommandItem("date", "显示当前的日期和时间", G_SYS),
    AdbCommandItem("free -h", "以人类易读的格式显示系统内存使用情况", G_SYS),
    AdbCommandItem("df -h", "以人类易读的格式显示所有分区的磁盘使用情况", G_SYS),
    AdbCommandItem("df -h /system", "显示 /system 分区的磁盘使用情况", G_SYS),
    AdbCommandItem("du -h", "显示文件和目录的磁盘占用情况", G_SYS),
    AdbCommandItem("du -sh /system/*", "汇总显示 /system 目录下各文件和目录的占用大小", G_SYS),
    AdbCommandItem("dmesg", "显示内核日志信息", G_SYS),

    AdbCommandItem("cmd uimode night yes", "开启系统 UI 的夜间模式", G_SHOW),
    AdbCommandItem("cmd uimode night no", "关闭系统 UI 的夜间模式", G_SHOW),
    AdbCommandItem("wm size <width>x<height>", "设置屏幕的分辨率", G_SHOW),
    AdbCommandItem("wm density <dpi>", "设置屏幕的显示密度（DPI）", G_SHOW),
    AdbCommandItem("wm overscan <left>,<top>,<right>,<bottom>", "设置显示器的过扫描区域", G_SHOW),
    AdbCommandItem("settings get <namespace> <key>", "获取指定系统设置项的数值", G_SHOW),
    AdbCommandItem("settings put <namespace> <key> <value>", "设置指定系统设置项的数值", G_SHOW),
    AdbCommandItem("settings delete <namespace> <key>", "删除指定的系统设置项", G_SHOW),
    AdbCommandItem("settings list secure", "列出 secure 命名空间下的全部设置项", G_SHOW),

    AdbCommandItem("dumpsys activity", "显示应用活动（Activity）及其状态的相关信息", G_DEBUG),
    AdbCommandItem("dumpsys battery", "显示电池状态和详细信息", G_DEBUG),
    AdbCommandItem("dumpsys battery set level <n>", "将电池电量设置为指定值（仅用于测试）", G_DEBUG),
    AdbCommandItem("dumpsys battery set status <n>", "将电池状态设置为指定值（仅用于测试）", G_DEBUG),
    AdbCommandItem("dumpsys battery reset", "重置电池统计信息", G_DEBUG),
    AdbCommandItem("dumpsys display", "显示显示系统的相关信息", G_DEBUG),
    AdbCommandItem("logcat", "显示系统日志内容", G_DEBUG),
    AdbCommandItem("logcat -c", "清空日志缓冲区", G_DEBUG),
    AdbCommandItem("logcat -g", "显示日志缓冲区的大小", G_DEBUG),
    AdbCommandItem("logcat -G <size>", "设置日志缓冲区的大小", G_DEBUG),
    AdbCommandItem("logcat | grep <keyword>", "过滤显示包含指定关键词的系统日志", G_DEBUG),

    AdbCommandItem("su", "切换到超级用户（root）模式", G_SEC, dangerous = true),
    AdbCommandItem("getenforce", "显示当前 SELinux 模式（强制、宽容或禁用）", G_SEC),
    AdbCommandItem("setenforce 0", "将 SELinux 临时切换为宽容模式，重启后失效", G_SEC, dangerous = true),
    AdbCommandItem("setenforce 1", "将 SELinux 临时切换为强制模式，重启后失效", G_SEC, dangerous = true),

    AdbCommandItem("ping <host>", "测试与指定主机的网络连通性", G_NET),
    AdbCommandItem("ping -c <n> <host>", "发送指定次数的 ICMP 包测试网络连通性", G_NET),
    AdbCommandItem("ifconfig", "显示或配置网络接口的信息（IP 地址、MAC 地址）", G_NET),
    AdbCommandItem("ip addr", "显示所有网络接口的 IP 地址信息（替代 ifconfig）", G_NET),
    AdbCommandItem("netstat", "显示网络连接状态和网络统计信息", G_NET),
    AdbCommandItem("nslookup <domain>", "查询指定域名的 DNS 解析结果", G_NET),
    AdbCommandItem("traceroute <host>", "跟踪数据包到指定主机的路由路径", G_NET),
    AdbCommandItem("iptables -L", "列出所有的 iptables 防火墙规则", G_NET),

    AdbCommandItem("reboot", "重启设备", G_POWER, dangerous = true),
    AdbCommandItem("reboot recovery", "重启设备进入 Recovery 模式", G_POWER, dangerous = true),
    AdbCommandItem("reboot bootloader", "重启设备进入 Bootloader（刷机）模式", G_POWER, dangerous = true),
    AdbCommandItem("shutdown -h now", "立即关闭设备，部分安卓设备需要 Root 权限", G_POWER, dangerous = true),

    AdbCommandItem("mount", "显示当前已挂载的文件系统", G_MOUNT),
    AdbCommandItem("mount -o rw,remount /system", "将 /system 分区重新挂载为可读写模式（需 Root）", G_MOUNT, dangerous = true),
    AdbCommandItem("umount <mount_point>", "卸载指定的文件系统", G_MOUNT),
    AdbCommandItem("fsck <partition>", "检查并修复指定分区的文件系统错误（需先卸载分区，慎用）", G_MOUNT, dangerous = true),
    AdbCommandItem("mkfs.ext4 <partition>", "将指定分区格式化为 ext4 文件系统（需 Root，慎用）", G_MOUNT, dangerous = true),

    AdbCommandItem("clear", "向终端发送清屏转义序列；本页请用输出卡右上角的「清空」", G_UTIL),
    AdbCommandItem("echo <message>", "在终端中打印指定的消息", G_UTIL),
    AdbCommandItem("grep <pattern> <file>", "在文件或输入内容中搜索指定的字符串模式", G_UTIL),
    AdbCommandItem("head <file_path>", "显示文件的前 10 行内容", G_UTIL),
    AdbCommandItem("tail <file_path>", "显示文件的最后 10 行内容", G_UTIL),
    AdbCommandItem("tail -f <file_path>", "实时跟踪文件内容的变化，常用于日志监控", G_UTIL),
    AdbCommandItem("wc <file_path>", "统计文件的行数、单词数和字节数", G_UTIL),
    AdbCommandItem("tar -czf <archive.tar.gz> <file/dir>", "将文件或目录压缩为 gzip 格式的 tar 包", G_UTIL),
    AdbCommandItem("tar -xzf <archive.tar.gz>", "解压 gzip 格式的 tar 包", G_UTIL),
    AdbCommandItem("zip <archive.zip> <file/dir>", "将文件或目录压缩为 ZIP 格式", G_UTIL),
    AdbCommandItem("unzip <archive.zip>", "解压 ZIP 格式的压缩包", G_UTIL),
    AdbCommandItem("sleep <seconds>", "暂停执行指定的秒数", G_UTIL),
    AdbCommandItem("which <command>", "查找指定命令的可执行文件路径", G_UTIL),
    AdbCommandItem("alias <name>=<command>", "创建命令别名；本页每条命令都是新 shell，别名只在那一次执行里有效", G_UTIL),
    AdbCommandItem("history", "列出 shell 的命令历史；本页每次都是新会话，通常为空", G_UTIL),
    AdbCommandItem("exit", "结束本次命令的 shell；对本页无影响，离开页面请用返回键", G_UTIL),
)
