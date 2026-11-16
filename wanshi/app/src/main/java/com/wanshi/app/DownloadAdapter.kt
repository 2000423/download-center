package com.wanshi.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadAdapter(private val onOpen: (DownloadStore.Task) -> Unit) :
    RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val data = ArrayList<DownloadStore.Task>()

    fun submit(items: List<DownloadStore.Task>) {
        data.clear()
        data.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(data[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvDetail: TextView = itemView.findViewById(R.id.tvDetail)
        private val pb: ProgressBar = itemView.findViewById(R.id.pb)

        fun bind(task: DownloadStore.Task) {
            tvName.text = task.name

            when (task.status) {
                DownloadStore.QUEUED -> {
                    tvStatus.text = "排队中"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.muted))
                    pb.isIndeterminate = false
                    pb.progress = 0
                }
                DownloadStore.RUNNING -> {
                    tvStatus.text = "下载中 " + task.pct + "% · " + task.note
                    tvStatus.setTextColor(itemView.context.getColor(R.color.accent))
                    pb.isIndeterminate = task.pct <= 0
                    pb.progress = task.pct
                }
                DownloadStore.DONE -> {
                    tvStatus.text = "已完成 · 点按播放"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.accent))
                    pb.isIndeterminate = false
                    pb.progress = 100
                }
                else -> {
                    tvStatus.text = "失败：" + task.error
                    tvStatus.setTextColor(itemView.context.getColor(R.color.danger))
                    pb.isIndeterminate = false
                    pb.progress = 0
                }
            }

            if (task.display.isNotBlank()) {
                tvDetail.visibility = View.VISIBLE
                tvDetail.text = "保存在 " + task.display
            } else {
                tvDetail.visibility = View.GONE
            }

            itemView.setOnClickListener {
                if (task.status == DownloadStore.DONE) onOpen(task)
            }
        }
    }
}
