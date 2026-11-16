package com.wanshi.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DetectAdapter(private val onAction: (MediaItem, Int) -> Unit) :
    RecyclerView.Adapter<DetectAdapter.VH>() {

    companion object {
        const val ACTION_COPY = 0
        const val ACTION_SAVE = 1
    }

    private val data = ArrayList<MediaItem>()

    fun submit(items: List<MediaItem>) {
        data.clear()
        data.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_detect, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(data[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val dot: View = itemView.findViewById(R.id.itemDot)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val btnCopy: TextView = itemView.findViewById(R.id.btnCopy)
        private val btnSave: TextView = itemView.findViewById(R.id.btnSave)

        fun bind(item: MediaItem) {
            tvTitle.text = item.title
            tvTime.text = Fmt.time(item.updatedAt)
            dot.setBackgroundResource(
                if (item.playing) R.drawable.dot_on else R.drawable.dot_off
            )

            val meta = StringBuilder()
            meta.append(if (item.appLabel.isNotBlank()) item.appLabel else item.packageName)
            if (item.artist.isNotBlank()) meta.append(" · ").append(item.artist)
            val dur = Fmt.duration(item.durationMs)
            if (dur.isNotEmpty()) meta.append(" · ").append(dur)
            meta.append(if (item.playing) " · 播放中" else " · 已暂停")
            if (item.fromNotification) meta.append(" · 来自通知")
            tvMeta.text = meta.toString()

            btnCopy.setOnClickListener { onAction(item, ACTION_COPY) }
            btnSave.setOnClickListener { onAction(item, ACTION_SAVE) }
        }
    }
}
