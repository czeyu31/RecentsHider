package com.example.hiderecents

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FilePickerActivity : AppCompatActivity() {

    private lateinit var tvPath: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var recyclerView: RecyclerView
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private val selectedFiles = mutableSetOf<File>()
    private var isMultiSelect = false
    private var fileFilter = ""

    data class FileItem(val file: File, val isDir: Boolean, val name: String, val info: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_picker)

        tvPath = findViewById(R.id.tvPath)
        tvTitle = findViewById(R.id.tvTitle)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        isMultiSelect = intent.getBooleanExtra("multi", false)
        fileFilter = intent.getStringExtra("filter") ?: ""

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            if (currentDir != Environment.getExternalStorageDirectory()) {
                currentDir = currentDir.parentFile ?: Environment.getExternalStorageDirectory()
                loadFiles()
            } else {
                finish()
            }
        }

        findViewById<TextView>(R.id.tvSelectAll).setOnClickListener {
            if (isMultiSelect) {
                val adapter = recyclerView.adapter as? FileAdapter ?: return@setOnClickListener
                if (selectedFiles.size == adapter.items.count { !it.isDir }) {
                    selectedFiles.clear()
                } else {
                    adapter.items.filter { !it.isDir }.forEach { selectedFiles.add(it.file) }
                }
                adapter.notifyDataSetChanged()
                updateSelectedCount()
            }
        }

        findViewById<TextView>(R.id.btnConfirm).setOnClickListener {
            val result = Intent()
            val paths = ArrayList(selectedFiles.map { it.absolutePath })
            result.putStringArrayListExtra("files", paths)
            setResult(RESULT_OK, result)
            finish()
        }

        loadFiles()
    }

    private fun loadFiles() {
        tvPath.text = currentDir.absolutePath
        tvTitle.text = if (isMultiSelect) "选择文件（可多选）" else "选择文件"

        val items = mutableListOf<FileItem>()
        try {
            val files = currentDir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
            for (file in files) {
                if (file.isHidden) continue
                if (file.isDirectory) {
                    items.add(FileItem(file, true, file.name, "${file.listFiles()?.size ?: 0} 项"))
                } else {
                    val ext = file.extension.lowercase()
                    if (fileFilter.isNotEmpty() && !fileFilter.contains(ext)) continue
                    val size = formatSize(file.length())
                    items.add(FileItem(file, false, file.name, size))
                }
            }
        } catch (_: Exception) {}

        recyclerView.adapter = FileAdapter(items)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        tvSelectedCount.text = "已选择 ${selectedFiles.size} 个文件"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    inner class FileAdapter(val items: List<FileItem>) : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvInfo: TextView = view.findViewById(R.id.tvInfo)
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file_picker, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvInfo.text = item.info

            if (item.isDir) {
                holder.ivIcon.setImageResource(R.drawable.ic_ft_folder)
                holder.cbSelect.visibility = View.GONE
            } else {
                if (item.name.endsWith(".apk", ignoreCase = true)) {
                    try {
                        val pm = packageManager
                        val pi = pm.getPackageArchiveInfo(item.file.absolutePath, android.content.pm.PackageManager.GET_META_DATA)
                        if (pi != null) {
                            pi.applicationInfo.sourceDir = item.file.absolutePath
                            pi.applicationInfo.publicSourceDir = item.file.absolutePath
                            holder.ivIcon.setImageDrawable(pi.applicationInfo.loadIcon(pm))
                        } else {
                            holder.ivIcon.setImageResource(R.drawable.ic_apk_install)
                        }
                    } catch (_: Exception) {
                        holder.ivIcon.setImageResource(R.drawable.ic_apk_install)
                    }
                } else {
                    holder.ivIcon.setImageResource(R.drawable.ic_ft_file)
                }
                holder.cbSelect.visibility = if (isMultiSelect) View.VISIBLE else View.GONE
                holder.cbSelect.isChecked = selectedFiles.contains(item.file)
            }

            holder.itemView.setOnClickListener {
                if (item.isDir) {
                    currentDir = item.file
                    loadFiles()
                } else if (isMultiSelect) {
                    if (selectedFiles.contains(item.file)) selectedFiles.remove(item.file)
                    else selectedFiles.add(item.file)
                    notifyItemChanged(position)
                    updateSelectedCount()
                } else {
                    selectedFiles.clear()
                    selectedFiles.add(item.file)
                    val result = Intent()
                    result.putStringArrayListExtra("files", arrayListOf(item.file.absolutePath))
                    setResult(RESULT_OK, result)
                    finish()
                }
            }

            if (isMultiSelect && !item.isDir) {
                holder.cbSelect.setOnClickListener {
                    if (holder.cbSelect.isChecked) selectedFiles.add(item.file)
                    else selectedFiles.remove(item.file)
                    updateSelectedCount()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onBackPressed() {
        if (currentDir != Environment.getExternalStorageDirectory()) {
            currentDir = currentDir.parentFile ?: Environment.getExternalStorageDirectory()
            loadFiles()
        } else {
            super.onBackPressed()
        }
    }
}
