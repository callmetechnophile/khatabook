/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * RecyclerView adapter for Chatbot conversation messages.
 */

package com.khatawas.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.khatawas.R
import com.khatawas.db.ChatMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageAdapter(
    private var messages: List<ChatMessageEntity> = emptyList()
) : RecyclerView.Adapter<ChatMessageAdapter.ChatViewHolder>() {

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    fun submitList(newMessages: List<ChatMessageEntity>) {
        this.messages = newMessages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val item = messages[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val llUser: LinearLayout = itemView.findViewById(R.id.ll_user_message)
        private val tvUserText: TextView = itemView.findViewById(R.id.tv_user_text)
        private val tvUserTime: TextView = itemView.findViewById(R.id.tv_user_time)

        private val llAssistant: LinearLayout = itemView.findViewById(R.id.ll_assistant_message)
        private val tvAssistantText: TextView = itemView.findViewById(R.id.tv_assistant_text)
        private val tvAssistantTime: TextView = itemView.findViewById(R.id.tv_assistant_time)
        private val tvSourceIndicator: TextView = itemView.findViewById(R.id.tv_source_indicator)

        fun bind(item: ChatMessageEntity) {
            val formattedTime = TIME_FORMAT.format(Date(item.timestamp))

            if (item.role == "user") {
                llUser.visibility = View.VISIBLE
                llAssistant.visibility = View.GONE
                tvUserText.text = item.content
                tvUserTime.text = formattedTime
            } else {
                llUser.visibility = View.GONE
                llAssistant.visibility = View.VISIBLE
                tvAssistantText.text = item.content
                tvAssistantTime.text = formattedTime

                if (item.sourceMetadata.isNotEmpty()) {
                    tvSourceIndicator.visibility = View.VISIBLE
                    tvSourceIndicator.text = item.sourceMetadata
                } else {
                    tvSourceIndicator.visibility = View.GONE
                }
            }
        }
    }
}
