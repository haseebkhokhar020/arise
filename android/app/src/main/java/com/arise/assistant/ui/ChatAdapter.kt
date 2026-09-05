package com.arise.assistant.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.arise.assistant.R
import com.arise.assistant.engine.ChatItem

/** Conversation list — lightweight single-text bubbles (no extra deps). */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<ChatItem>()
    private var scrollToBottom = false

    fun submit(list: List<ChatItem>) {
        val changed = items.size != list.size || (items.isNotEmpty() && list.isNotEmpty() && items.last() != list.last())
        items.clear(); items.addAll(list)
        scrollToBottom = true
        if (changed || list.isEmpty()) notifyDataSetChanged()
    }

    fun consumeScroll(): Boolean = scrollToBottom.also { scrollToBottom = false }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val tv = holder.tv
        val ctx = tv.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        tv.text = item.text
        tv.typeface = Typeface.DEFAULT
        tv.textSize = if (item.kind == "debug") 10f else 14f
        tv.setTextColor(when (item.kind) {
            "user" -> Color.WHITE
            "assistant" -> 0xFFEEF1FF.toInt()
            "debug" -> 0xFF7A86B5.toInt()
            else -> 0xFF9AA3C0.toInt()
        })
        tv.setBackgroundResource(when (item.kind) {
            "user" -> R.drawable.bg_bubble_user
            "assistant" -> R.drawable.bg_bubble_assistant
            else -> R.drawable.bg_bubble_system
        })
        if (item.kind == "debug") tv.typeface = Typeface.MONOSPACE

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (item.kind == "user") Gravity.END else Gravity.START
        val mx = dp(12)
        lp.setMargins(if (item.kind == "user") mx * 3 else mx, dp(3),
            if (item.kind == "user") mx else mx * 3, dp(3))
        tv.layoutParams = lp
        tv.setPadding(dp(14), dp(9), dp(14), dp(9))
        tv.maxWidth = (tv.resources.displayMetrics.widthPixels * 0.86f).toInt()
    }
}
