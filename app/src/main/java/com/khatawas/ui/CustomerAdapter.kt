/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Rural-First Customer Directory Adapter with distinct family account tracking and 1-tap transaction actions.
 */

package com.khatawas.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.khatawas.R
import com.khatawas.auth.PrivacyManager

data class CustomerUiModel(
    val name: String,
    val balancePaise: Long,
    val isFamilyRelation: Boolean = false,
    val relationNote: String = ""
) {
    fun getDisplayBalance(isUnlocked: Boolean): String {
        return if (isUnlocked) {
            IndianCurrencyFormatter.formatPaise(balancePaise)
        } else {
            PrivacyManager.MASK_VALUE
        }
    }

    fun getDisplayName(isUnlocked: Boolean, maskCustomerNames: Boolean): String {
        return if (isUnlocked || !maskCustomerNames) {
            name
        } else {
            PrivacyManager.MASK_VALUE
        }
    }
}

class CustomerAdapter(
    var isUnlocked: Boolean = false,
    var maskCustomerNames: Boolean = true,
    private val onCustomerClick: (CustomerUiModel) -> Unit,
    private val onGiveCreditClick: (CustomerUiModel) -> Unit,
    private val onReceivePaymentClick: (CustomerUiModel) -> Unit
) : ListAdapter<CustomerUiModel, CustomerAdapter.CustomerViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<CustomerUiModel>() {
        override fun areItemsTheSame(oldItem: CustomerUiModel, newItem: CustomerUiModel): Boolean {
            return oldItem.name.equals(newItem.name, ignoreCase = true)
        }

        override fun areContentsTheSame(oldItem: CustomerUiModel, newItem: CustomerUiModel): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return CustomerViewHolder(view, onCustomerClick, onGiveCreditClick, onReceivePaymentClick)
    }

    override fun onBindViewHolder(holder: CustomerViewHolder, position: Int) {
        holder.bind(getItem(position), isUnlocked, maskCustomerNames)
    }

    class CustomerViewHolder(
        itemView: View,
        private val onCustomerClick: (CustomerUiModel) -> Unit,
        private val onGiveCreditClick: (CustomerUiModel) -> Unit,
        private val onReceivePaymentClick: (CustomerUiModel) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvInitial: TextView = itemView.findViewById(R.id.tv_customer_initial)
        private val tvName: TextView = itemView.findViewById(R.id.tv_customer_row_name)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_customer_row_subtitle)
        private val tvBalance: TextView = itemView.findViewById(R.id.tv_customer_row_balance)
        private val btnGiveCredit: Button = itemView.findViewById(R.id.btn_row_give_credit)
        private val btnReceivePayment: Button = itemView.findViewById(R.id.btn_row_receive_payment)

        fun bind(item: CustomerUiModel, isUnlocked: Boolean, maskCustomerNames: Boolean) {
            val displayName = item.getDisplayName(isUnlocked, maskCustomerNames)
            val displayBalance = item.getDisplayBalance(isUnlocked)

            tvInitial.text = if (item.name.isNotBlank()) item.name.trim().take(1).uppercase() else "C"
            tvName.text = displayName
            tvBalance.text = displayBalance

            if (item.balancePaise > 0L) {
                tvBalance.setTextColor(ContextCompat.getColor(itemView.context, R.color.btn_credit_bg))
                tvSubtitle.text = if (item.isFamilyRelation) "Family Account • Owes Udhaar" else "Owes Udhaar"
            } else if (item.balancePaise < 0L) {
                tvBalance.setTextColor(ContextCompat.getColor(itemView.context, R.color.btn_debit_bg))
                tvSubtitle.text = "Advance payment balance"
            } else {
                tvBalance.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
                tvSubtitle.text = "All settled • No pending dues"
            }

            itemView.setOnClickListener {
                onCustomerClick(item)
            }

            btnGiveCredit.setOnClickListener {
                onGiveCreditClick(item)
            }

            btnReceivePayment.setOnClickListener {
                onReceivePaymentClick(item)
            }
        }
    }
}
