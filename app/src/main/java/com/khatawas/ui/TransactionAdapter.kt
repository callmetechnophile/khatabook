/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * RecyclerView adapter for customer ledger transactions supporting CREDIT, DEBIT, STUCK, and ILLEGAL badges.
 */

package com.khatawas.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.khatawas.R

class TransactionAdapter(
    private val onItemClick: ((TransactionUiModel) -> Unit)? = null
) : ListAdapter<TransactionUiModel, TransactionAdapter.TransactionViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<TransactionUiModel>() {
        override fun areItemsTheSame(oldItem: TransactionUiModel, newItem: TransactionUiModel): Boolean {
            return oldItem.id == newItem.id && oldItem.eventId == newItem.eventId
        }

        override fun areContentsTheSame(oldItem: TransactionUiModel, newItem: TransactionUiModel): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TransactionViewHolder(
        itemView: View,
        private val onItemClick: ((TransactionUiModel) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvCustomerName: TextView = itemView.findViewById(R.id.tv_customer_name)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_amount)
        private val tvCategoryBadge: TextView = itemView.findViewById(R.id.tv_category_badge)
        private val tvPaymentMethod: TextView = itemView.findViewById(R.id.tv_payment_method)
        private val tvReviewStatus: TextView = itemView.findViewById(R.id.tv_review_status)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
        private val tvNote: TextView = itemView.findViewById(R.id.tv_note)

        fun bind(item: TransactionUiModel) {
            val context = itemView.context

            tvCustomerName.text = item.customerName
            tvAmount.text = item.formattedAmount
            tvPaymentMethod.text = item.paymentMethod
            tvReviewStatus.text = item.reviewStatus
            tvTimestamp.text = item.formattedDateTime

            // Category badge presentation: CREDIT (Green), DEBIT (Red), STUCK (Yellow), ILLEGAL (Grey)
            tvCategoryBadge.text = item.category.label
            tvCategoryBadge.setBackgroundResource(item.category.bgDrawableRes)
            tvCategoryBadge.setTextColor(ContextCompat.getColor(context, item.category.textColorRes))
            tvCategoryBadge.contentDescription = "Category: ${item.category.label}. ${item.category.meaning}"

            // Amount text color matches category semantics
            tvAmount.setTextColor(ContextCompat.getColor(context, item.category.textColorRes))

            // Transaction description / user note
            if (item.note.isNotBlank()) {
                tvNote.text = item.note
                tvNote.visibility = View.VISIBLE
            } else {
                tvNote.visibility = View.GONE
            }

            // Accessibility: comprehensive content description
            itemView.contentDescription = item.accessibleDescription

            itemView.setOnClickListener {
                onItemClick?.invoke(item)
            }
        }
    }
}
