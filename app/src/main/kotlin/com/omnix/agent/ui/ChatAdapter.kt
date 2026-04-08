package com.omnix.agent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

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
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
            else      -> AiVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserVH -> holder.bind(msg)
            is AiVH   -> holder.bind(msg)
        }
    }

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        fun bind(msg: ChatMessage) { tvMessage.text = msg.text }
    }

    class AiVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        fun bind(msg: ChatMessage) { tvMessage.text = msg.text }
    }
}
