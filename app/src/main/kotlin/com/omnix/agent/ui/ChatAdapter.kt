package com.omnix.agent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    // Markwon instance is created lazily from the first parent context seen.
    // It is safe to reuse across bind calls — Markwon is thread-safe for rendering.
    private var markwon: Markwon? = null

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI   = 1
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (markwon == null) {
            markwon = Markwon.builder(parent.context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(parent.context))
                .usePlugin(LinkifyPlugin.create())
                .build()
        }
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
            else      -> AiVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserVH -> holder.bind(msg, timeFmt)
            is AiVH   -> holder.bind(msg, timeFmt, markwon)
        }
    }

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView   = view.findViewById(R.id.tv_message)
        private val tvTimestamp: TextView = view.findViewById(R.id.tv_timestamp)
        fun bind(msg: ChatMessage, fmt: SimpleDateFormat) {
            tvMessage.text   = msg.text
            tvTimestamp.text = fmt.format(Date(msg.timestamp))
        }
    }

    class AiVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView   = view.findViewById(R.id.tv_message)
        private val tvTimestamp: TextView = view.findViewById(R.id.tv_timestamp)
        fun bind(msg: ChatMessage, fmt: SimpleDateFormat, markwon: Markwon?) {
            if (markwon != null) {
                markwon.setMarkdown(tvMessage, msg.text)
            } else {
                tvMessage.text = msg.text
            }
            tvTimestamp.text = fmt.format(Date(msg.timestamp))
        }
    }
}
