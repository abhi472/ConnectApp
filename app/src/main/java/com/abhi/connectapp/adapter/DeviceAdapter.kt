package com.abhi.connectapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.abhi.connectapp.databinding.ItemDeviceBinding // Added this import
import com.abhi.connectapp.model.Device

class DeviceAdapter(
    private val onItemClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<Device>()

    fun addDevice(device: Device) {
        // Simple check to avoid duplicates for now, based on address
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clearDevices() {
        devices.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                onItemClick(devices[adapterPosition])
            }
        }

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.name ?: "Unknown Device"
            binding.tvDeviceAddress.text = device.address
        }
    }
}
