package com.wanshi.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadAdapter
    private lateinit var emptyView: View

    private val storeListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        emptyView = findViewById(R.id.emptyDownloads)
        val rv = findViewById<RecyclerView>(R.id.rvDownloads)
        adapter = DownloadAdapter { task -> openTask(task) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            DownloadStore.clearFinished()
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.observe(storeListener)
        render()
    }

    override fun onStop() {
        DownloadStore.unobserve(storeListener)
        super.onStop()
    }

    private fun render() {
        val tasks = DownloadStore.list()
        adapter.submit(tasks)
        emptyView.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openTask(task: DownloadStore.Task) {
        val uri = task.uri
        if (uri == null) {
            Toast.makeText(this, "文件位置未知：" + task.display, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, task.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 部分播放器只认 clipData 里的 uri，补上能提高兼容性
        intent.clipData = ClipData.newRawUri(task.name, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的播放器，文件在：\n" + task.display, Toast.LENGTH_LONG)
                .show()
        }
    }
}
