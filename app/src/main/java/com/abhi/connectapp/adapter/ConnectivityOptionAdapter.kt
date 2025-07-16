package com.abhi.connectapp.adapter


import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.abhi.connectapp.databinding.ItemConnectivityOptionBinding
import com.abhi.connectapp.model.ConnectivityOption

class ConnectivityOptionAdapter(
    private val options: List<ConnectivityOption>,
    private val onItemClick: (ConnectivityOption) -> Unit
) : RecyclerView.Adapter<ConnectivityOptionAdapter.OptionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val binding = ItemConnectivityOptionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        val option = options[position]
        holder.bind(option)
    }

    override fun getItemCount(): Int = options.size

    inner class OptionViewHolder(private val binding: ItemConnectivityOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                onItemClick(options[adapterPosition])
            }
        }

        fun bind(option: ConnectivityOption) {
            binding.tvOptionName.text = option.name
        }
    }
}