package com.andrew.proxyapp.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConnectionRecord
import com.andrew.proxyapp.manager.RuntimeController
import kotlinx.coroutines.launch
import java.util.Locale

@Suppress("NotifyDataSetChanged")
class ConnectionsActivity : AppCompatActivity() {
    private val adapter = ConnectionAdapter { RuntimeController.closeConnection(it.id) }
    private var all = emptyList<ConnectionRecord>()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connections)
        configureSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@ConnectionsActivity)
            adapter = this@ConnectionsActivity.adapter
        }
        findViewById<TextInputEditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            RuntimeController.connections.collect { all = it; filter() }
        } }
    }

    private fun filter() {
        val filtered = if (query.isBlank()) all else all.filter {
            listOf(it.domain, it.destination, it.protocol, it.rule, it.outbound).any { value -> value.contains(query, true) }
        }
        adapter.submit(filtered)
        findViewById<TextView>(R.id.emptyView).apply {
            visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            text = getString(R.string.connection_count, all.size)
        }
    }
}

@Suppress("NotifyDataSetChanged")
private class ConnectionAdapter(private val close: (ConnectionRecord) -> Unit) : RecyclerView.Adapter<ConnectionAdapter.VH>() {
    private var items = emptyList<ConnectionRecord>()
    fun submit(value: List<ConnectionRecord>) { items = value; notifyDataSetChanged() }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val close: ImageButton = view.findViewById(R.id.closeButton)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_connection, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.domain.ifBlank { item.destination }
        holder.subtitle.text = holder.itemView.context.getString(
            R.string.connection_detail,
            item.protocol.uppercase(),
            item.outbound,
            bytes(item.uploadTotal),
            bytes(item.downloadTotal)
        )
        holder.close.setOnClickListener { close(item) }
    }
    override fun getItemCount() = items.size
    companion object {
        private fun bytes(value: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB")
            var number = value.toDouble(); var unit = 0
            while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
            return String.format(Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", number, units[unit])
        }
    }
}
