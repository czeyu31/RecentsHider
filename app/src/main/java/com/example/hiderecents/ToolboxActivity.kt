package com.example.hiderecents

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class ToolboxActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolbox)

        val panel = findViewById<LinearLayout>(R.id.toolboxPanel)
        val closeBtn = findViewById<ImageView>(R.id.btnClose)

        closeBtn.setOnClickListener { closePanel() }

        findViewById<LinearLayout>(R.id.toolAdb).setOnClickListener {
            startActivity(Intent(this, AdbActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.toolAppManage).setOnClickListener {
            startActivity(Intent(this, AppManageActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.toolNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationHistoryActivity::class.java))
        }
    }

    private fun closePanel() {
        finish()
        overridePendingTransition(0, R.anim.scale_out)
    }

    override fun onBackPressed() {
        closePanel()
    }
}
