package com.omnix.agent.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatSessionDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_session_detail)

        val sessionId    = intent.getStringExtra("session_id") ?: return
        val sessionTitle = intent.getStringExtra("session_title") ?: "Chat"

        val tvTitle   = findViewById<TextView>(R.id.tv_session_detail_title)
        val rv        = findViewById<RecyclerView>(R.id.rv_session_messages)
        val tvEmpty   = findViewById<TextView>(R.id.tv_empty_detail)

        tvTitle.text = sessionTitle
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val adapter = ChatAdapter()
        val layout  = LinearLayoutManager(this).also { it.stackFromEnd = true }
        rv.layoutManager = layout
        rv.adapter = adapter

        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                OmnixDatabase.getInstance(applicationContext)
                    .chatMessageDao()
                    .getForSession(sessionId)
            }
            if (messages.isEmpty()) {
                rv.visibility     = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                rv.visibility     = View.VISIBLE
                tvEmpty.visibility = View.GONE
                messages.forEach { entity ->
                    adapter.addMessage(
                        ChatMessage(
                            text      = entity.text,
                            isUser    = entity.isUser,
                            timestamp = entity.timestamp
                        )
                    )
                }
                rv.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }
}
