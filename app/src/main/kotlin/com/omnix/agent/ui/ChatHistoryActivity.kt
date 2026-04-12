package com.omnix.agent.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnix.agent.R
import com.omnix.agent.database.ChatSessionEntity
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatHistoryActivity : AppCompatActivity() {

    private lateinit var rvSessions: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = SessionAdapter(
        onClick  = { session -> openSession(session) },
        onDelete = { session -> confirmDelete(session) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_history)

        rvSessions = findViewById(R.id.rv_sessions)
        tvEmpty    = findViewById(R.id.tv_empty)

        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = adapter

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) {
                OmnixDatabase.getInstance(applicationContext).chatSessionDao().getRecent(100)
            }
            if (sessions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvSessions.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvSessions.visibility = View.VISIBLE
                adapter.submitList(sessions)
            }
        }
    }

    private fun openSession(session: ChatSessionEntity) {
        startActivity(
            Intent(this, ChatSessionDetailActivity::class.java)
                .putExtra("session_id", session.id)
                .putExtra("session_title", session.title)
        )
    }

    private fun confirmDelete(session: ChatSessionEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session_title)
            .setMessage(getString(R.string.delete_session_message, session.title))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteSession(session) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteSession(session: ChatSessionEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = OmnixDatabase.getInstance(applicationContext)
                db.chatMessageDao().deleteForSession(session.id)
                db.chatSessionDao().delete(session.id)
            }
            Toast.makeText(this@ChatHistoryActivity, getString(R.string.session_deleted), Toast.LENGTH_SHORT).show()
            loadSessions()
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class SessionAdapter(
        private val onClick: (ChatSessionEntity) -> Unit,
        private val onDelete: (ChatSessionEntity) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private val items = mutableListOf<ChatSessionEntity>()

        fun submitList(list: List<ChatSessionEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_session, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onClick, onDelete)
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle: TextView = view.findViewById(R.id.tv_session_title)
            private val tvDate: TextView  = view.findViewById(R.id.tv_session_date)
            private val tvMsgs: TextView  = view.findViewById(R.id.tv_session_msgs)

            fun bind(
                session: ChatSessionEntity,
                onClick: (ChatSessionEntity) -> Unit,
                onDelete: (ChatSessionEntity) -> Unit
            ) {
                tvTitle.text = session.title.ifBlank { itemView.context.getString(R.string.chat_session_default_title) }
                tvDate.text  = formatDate(session.startedAt)
                tvMsgs.text  = "${session.messageCount} messages"
                itemView.setOnClickListener { onClick(session) }
                itemView.setOnLongClickListener { onDelete(session); true }
            }

            private fun formatDate(ts: Long): String =
                SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(ts))
        }
    }
}
