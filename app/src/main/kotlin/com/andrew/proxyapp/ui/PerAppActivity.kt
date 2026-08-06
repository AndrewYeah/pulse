package com.andrew.proxyapp.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.PerAppMode
import com.andrew.proxyapp.data.ProxyConfig
import com.andrew.proxyapp.manager.ProxyManager

data class AppEntry(val label: String, val packageName: String, val icon: Drawable?, val missing: Boolean = false)

@Suppress("NotifyDataSetChanged")
class PerAppActivity : AppCompatActivity() {
    private val store by lazy { ConfigStore.get(this) }
    private lateinit var adapter: AppAdapter
    private var entries = emptyList<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_per_app)
        configureSystemBars()
        val settings = store.getSettings()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val selected = settings.selectedPackages.toMutableSet()
        val assignments = settings.appNodeAssignments.toMutableMap()
        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        modeGroup.check(if (settings.perAppMode == PerAppMode.INCLUDE_SELECTED) R.id.includeMode else R.id.excludeMode)
        adapter = AppAdapter(selected, assignments, store.getAllConfigs()) {
            if (modeGroup.checkedButtonId == R.id.includeMode) PerAppMode.INCLUDE_SELECTED else PerAppMode.EXCLUDE_SELECTED
        }
        findViewById<RecyclerView>(R.id.recyclerView).apply { layoutManager = LinearLayoutManager(this@PerAppActivity); adapter = this@PerAppActivity.adapter }
        entries = loadApps(selected + assignments.keys)
        adapter.submit(entries)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.includeMode) selected.addAll(assignments.keys) else selected.removeAll(assignments.keys)
            adapter.notifyDataSetChanged()
        }
        findViewById<TextInputEditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                adapter.submit(if (q.isBlank()) entries else entries.filter { it.label.contains(q, true) || it.packageName.contains(q, true) })
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<View>(R.id.appListPermissionHint).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { button ->
            store.updateSettings { current ->
                current.selectedPackages = selected
                current.appNodeAssignments = assignments
                current.perAppMode = if (modeGroup.checkedButtonId == R.id.includeMode) PerAppMode.INCLUDE_SELECTED else PerAppMode.EXCLUDE_SELECTED
            }
            Snackbar.make(button, R.string.settings_saved, Snackbar.LENGTH_LONG).apply {
                if (ProxyManager.isRunning) setAction(R.string.reconnect_now) { ProxyManager.restart(this@PerAppActivity) }
            }.show()
        }
    }

    private fun loadApps(selected: Set<String>): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
        val visible = packageManager.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { info ->
            if (!info.enabled || info.packageName == packageName) return@mapNotNull null
            val isUserInstalled = info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (!isUserInstalled && info.packageName !in launcherPackages && info.packageName !in selected) {
                return@mapNotNull null
            }
            AppEntry(
                info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                info.packageName,
                info.loadIcon(packageManager)
            )
        }.distinctBy { it.packageName }.toMutableList()
        val known = visible.mapTo(mutableSetOf()) { it.packageName }
        selected.filterNot(known::contains).forEach { visible += AppEntry(it, it, null, true) }
        return visible.sortedBy { it.label.lowercase() }
    }
}

@Suppress("NotifyDataSetChanged")
private class AppAdapter(
    private val selected: MutableSet<String>,
    private val assignments: MutableMap<String, String>,
    private val configs: List<ProxyConfig>,
    private val mode: () -> PerAppMode
) : RecyclerView.Adapter<AppAdapter.VH>() {
    private var items = emptyList<AppEntry>()
    fun submit(value: List<AppEntry>) { items = value; notifyDataSetChanged() }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon); val name: TextView = view.findViewById(R.id.name)
        val packageName: TextView = view.findViewById(R.id.packageName); val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)
        val nodeButton: MaterialButton = view.findViewById(R.id.nodeButton)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.name.text = if (item.missing) "${item.label} (${holder.itemView.context.getString(R.string.not_installed)})" else item.label
        holder.packageName.text = item.packageName
        val assignedId = assignments[item.packageName]
        val assignedConfig = configs.firstOrNull { it.id == assignedId }
        holder.nodeButton.text = when {
            assignedId == null -> holder.itemView.context.getString(R.string.follow_current_node)
            assignedConfig != null -> assignedConfig.name.ifBlank { assignedConfig.displayInfo }
            else -> holder.itemView.context.getString(R.string.node_unavailable)
        }
        holder.nodeButton.setOnClickListener {
            val labels = listOf(holder.itemView.context.getString(R.string.follow_current_node)) +
                configs.map { config -> config.name.ifBlank { config.displayInfo } }
            val checked = configs.indexOfFirst { it.id == assignedId }.let { if (it < 0) 0 else it + 1 }
            val dialog = AlertDialog.Builder(holder.itemView.context)
                .setTitle(R.string.select_app_node)
                .setSingleChoiceItems(labels.toTypedArray(), checked, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.listView.setOnItemClickListener { _, _, which, _ ->
                    if (which == 0) {
                        assignments.remove(item.packageName)
                    } else {
                        assignments[item.packageName] = configs[which - 1].id
                        if (mode() == PerAppMode.INCLUDE_SELECTED) selected.add(item.packageName) else selected.remove(item.packageName)
                    }
                    notifyItemChanged(position)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
        holder.checkbox.setOnCheckedChangeListener(null); holder.checkbox.isChecked = item.packageName in selected
        val updateSelection = { checked: Boolean ->
            if (checked) selected.add(item.packageName) else selected.remove(item.packageName)
            if ((mode() == PerAppMode.INCLUDE_SELECTED && !checked) || (mode() == PerAppMode.EXCLUDE_SELECTED && checked)) {
                assignments.remove(item.packageName)
            }
            notifyItemChanged(position)
        }
        val toggle = { updateSelection(item.packageName !in selected) }
        holder.itemView.setOnClickListener { toggle() }
        holder.checkbox.setOnCheckedChangeListener { _, checked -> updateSelection(checked) }
    }
    override fun getItemCount() = items.size
}
