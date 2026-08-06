package com.andrew.proxyapp.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.NodeLatency
import com.andrew.proxyapp.data.NodeQuery
import com.andrew.proxyapp.data.NodeQueryEngine
import com.andrew.proxyapp.data.NodeSort
import com.andrew.proxyapp.data.ProtocolType
import com.andrew.proxyapp.data.ProxyConfig
import com.andrew.proxyapp.databinding.ItemConfigBinding
import com.andrew.proxyapp.manager.ProxyManager
import com.andrew.proxyapp.manager.RuntimeController
import kotlinx.coroutines.launch

@Suppress("NotifyDataSetChanged")
class ConfigListActivity : AppCompatActivity() {
    private lateinit var store: ConfigStore
    private lateinit var adapter: ConfigAdapter
    private var query = ""
    private var protocolFilter: ProtocolType? = null
    private var nodeSort = NodeSort.NAME
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_list)
        configureSystemBars()
        store = ConfigStore.get(this)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_subscriptions -> {
                    launcher.launch(Intent(this, SubscriptionActivity::class.java))
                    true
                }
                R.id.action_test_latency -> {
                    testAllNodes()
                    true
                }
                R.id.action_filter_nodes -> {
                    showProtocolFilter()
                    true
                }
                R.id.action_sort_nodes -> {
                    showSortChoice()
                    true
                }
                else -> false
            }
        }
        // actionLayout 的自定义视图需要单独设置点击事件
        toolbar.menu.findItem(R.id.action_subscriptions)?.actionView?.setOnClickListener {
            launcher.launch(Intent(this, SubscriptionActivity::class.java))
        }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { launcher.launch(Intent(this, EditConfigActivity::class.java)) }
        adapter = ConfigAdapter(this, mutableListOf(), "", emptyMap(), emptyMap(), ::select, ::edit, ::delete)
        findViewById<RecyclerView>(R.id.recyclerView).apply { layoutManager = LinearLayoutManager(this@ConfigListActivity); adapter = this@ConfigListActivity.adapter }
        findViewById<TextInputEditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); refreshList() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { RuntimeController.latencies.collect { adapter.latencies = it; adapter.notifyDataSetChanged() } }
            launch { RuntimeController.selectedConfigId.collect { if (it.isNotBlank()) { adapter.activeId = it; adapter.notifyDataSetChanged() } } }
        } }
        refreshList()
    }

    private fun testAllNodes() {
        if (ProxyManager.isRunning) RuntimeController.testAllNodes()
        else Toast.makeText(this, R.string.test_latency, Toast.LENGTH_SHORT).show()
    }

    private fun select(config: ProxyConfig) {
        if (config.unsupportedReason.isNotBlank() || config.validationError.isNotBlank()) {
            Toast.makeText(this, config.unsupportedReason.ifBlank { config.validationError }, Toast.LENGTH_LONG).show()
            return
        }
        if (!ProxyManager.isRunning) {
            store.setActiveConfig(config.id)
            adapter.activeId = config.id
            setResult(RESULT_OK)
            finish()
            return
        }
        lifecycleScope.launch {
            if (RuntimeController.selectConfig(config.id)) {
                store.setActiveConfig(config.id)
                adapter.activeId = config.id
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@ConfigListActivity, R.string.node_switch_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun edit(config: ProxyConfig) = launcher.launch(Intent(this, EditConfigActivity::class.java).putExtra("config_id", config.id))

    private fun delete(config: ProxyConfig) {
        val wasActive = store.getSettings().activeConfigId == config.id
        AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(getString(R.string.delete_profile_message, config.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteConfig(config.id); refreshList(); setResult(RESULT_OK)
                if (ProxyManager.isRunning && store.getAllConfigs().isEmpty()) ProxyManager.stop(this)
                Snackbar.make(findViewById(R.id.recyclerView), getString(R.string.deleted_profile, config.name), Snackbar.LENGTH_LONG).setAction(R.string.undo) {
                    store.saveConfig(config); if (wasActive) store.setActiveConfig(config.id); refreshList()
                }.show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun refreshList() {
        val sourceNames = store.getSubscriptions().associate { it.id to it.name }
        val values = NodeQueryEngine.apply(
            store.getAllConfigs(),
            NodeQuery(text = query, protocol = protocolFilter, sort = nodeSort),
            RuntimeController.latencies.value
        ).toMutableList()
        adapter.configs = values; adapter.activeId = store.getSettings().activeConfigId; adapter.subscriptionNames = sourceNames; adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.emptyView).visibility = if (values.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<RecyclerView>(R.id.recyclerView).visibility = if (values.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showProtocolFilter() {
        val items = listOf(getString(R.string.node_filter_all)) + ProtocolType.entries.map { it.localizedLabel(this) }
        val selected = protocolFilter?.let { ProtocolType.entries.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.protocol)
            .setSingleChoiceItems(items.toTypedArray(), selected) { dialog, which ->
                protocolFilter = ProtocolType.entries.getOrNull(which - 1)
                dialog.dismiss()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSortChoice() {
        val items = arrayOf(
            getString(R.string.node_sort_name),
            getString(R.string.node_sort_protocol),
            getString(R.string.node_sort_latency),
            getString(R.string.node_sort_updated)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_nodes_title)
            .setSingleChoiceItems(items, nodeSort.ordinal) { dialog, which ->
                nodeSort = NodeSort.entries.getOrElse(which) { NodeSort.NAME }
                dialog.dismiss()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

private data class ConfigGroup(val key: String, val title: String, val configs: List<ProxyConfig>)
private sealed class ConfigRow {
    data class Group(val value: ConfigGroup) : ConfigRow()
    data class Node(val value: ProxyConfig) : ConfigRow()
}

@Suppress("NotifyDataSetChanged")
class ConfigAdapter(
    private val context: android.content.Context,
    configs: MutableList<ProxyConfig>, activeId: String,
    var latencies: Map<String, NodeLatency>, subscriptionNames: Map<String, String>,
    private val onSelect: (ProxyConfig) -> Unit, private val onEdit: (ProxyConfig) -> Unit,
    private val onDelete: (ProxyConfig) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val expanded = mutableSetOf<String>()
    private var rows: List<ConfigRow> = emptyList()
    var configs: MutableList<ProxyConfig> = configs
        set(value) { field = value; rebuild() }
    var activeId: String = activeId
        set(value) { field = value; rebuild() }
    var subscriptionNames: Map<String, String> = subscriptionNames
        set(value) { field = value; rebuild() }

    init { rebuild() }

    class VH(val binding: ItemConfigBinding) : RecyclerView.ViewHolder(binding.root)
    class GroupVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.groupTitle)
        val subtitle: TextView = view.findViewById(R.id.groupSubtitle)
        val arrow: android.widget.ImageButton = view.findViewById(R.id.groupArrow)
        val root: android.view.View = view.findViewById(R.id.groupRoot)
    }

    override fun getItemViewType(position: Int) = if (rows[position] is ConfigRow.Group) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 0) GroupVH(LayoutInflater.from(parent.context).inflate(R.layout.item_config_group, parent, false))
        else VH(ItemConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is ConfigRow.Group) {
            val group = row.value
            val groupHolder = holder as? GroupVH ?: return
            groupHolder.title.text = group.title
            groupHolder.subtitle.text = groupHolder.itemView.context.getString(R.string.node_group_count, group.configs.size)
            groupHolder.arrow.rotation = if (group.key in expanded) 180f else 0f
            groupHolder.root.setOnClickListener {
                if (!expanded.add(group.key)) expanded.remove(group.key)
                rebuild()
            }
            return
        }
        val config = (row as ConfigRow.Node).value
        val nodeHolder = holder as? VH ?: return
        with(nodeHolder.binding) {
            tvName.text = config.name.ifBlank { config.protocol.localizedLabel(context) }
            val source = subscriptionNames[config.subscriptionId]?.takeIf(String::isNotBlank)
            tvInfo.text = listOfNotNull(source, config.displayInfo).joinToString(" · ")
            val warning = config.unsupportedReason.ifBlank { config.validationError }.takeIf(String::isNotBlank)
            if (warning != null) tvInfo.text = root.context.getString(R.string.node_info_warning, tvInfo.text, warning)
            radioSelected.isChecked = config.id == activeId
            tvLatency.text = latencies[config.id]?.let { "${it.delayMs} ms" } ?: "—"
            val margins = root.layoutParams as? ViewGroup.MarginLayoutParams
            margins?.let { it.marginStart = 16; it.marginEnd = 4; root.layoutParams = it }
            root.setOnClickListener { onSelect(config) }; btnEdit.setOnClickListener { onEdit(config) }; btnDelete.setOnClickListener { onDelete(config) }
        }
    }

    override fun getItemCount() = rows.size

    private fun rebuild() {
        val grouped = configs.groupBy { config ->
            if (config.subscriptionId.isNotBlank()) "subscription:${config.subscriptionId}" else "server:${config.server}"
        }
        rows = grouped.flatMap { (key, nodes) ->
            val title = if (key.startsWith("subscription:")) {
                subscriptionNames[key.removePrefix("subscription:")].orEmpty().ifBlank { context.getString(R.string.subscriptions) }
            } else nodes.firstOrNull()?.server?.ifBlank { context.getString(R.string.manual_nodes) }
                ?: context.getString(R.string.manual_nodes)
            buildList {
                add(ConfigRow.Group(ConfigGroup(key, title, nodes)))
                if (key in expanded) nodes.forEach { add(ConfigRow.Node(it)) }
            }
        }
        notifyDataSetChanged()
    }

}
