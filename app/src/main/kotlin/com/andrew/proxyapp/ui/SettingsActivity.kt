package com.andrew.proxyapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.AppSettings
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.MatchType
import com.andrew.proxyapp.data.RuleAction
import com.andrew.proxyapp.data.RoutingRule
import com.andrew.proxyapp.data.RoutingGroup
import com.andrew.proxyapp.data.ThemeMode
import com.andrew.proxyapp.manager.ProxyManager
import com.andrew.proxyapp.databinding.ItemConfigBinding

@Suppress("NotifyDataSetChanged")
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var settings: AppSettings
    private lateinit var rulesAdapter: RoutingRulesAdapter

    private lateinit var cbChinaDirect: CompoundButton
    private lateinit var cbLanDirect: CompoundButton
    private lateinit var cbBlockAds: CompoundButton
    private lateinit var cbSkipCertVerify: CompoundButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routing)
        configureSystemBars()

        store = ConfigStore.get(this)
        settings = store.getSettings()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        bindImmediateChanges()

        findViewById<MaterialButton>(R.id.btnAddRule).setOnClickListener {
            showRuleDialog(null)
        }

        findViewById<MaterialButton>(R.id.btnRuleSets).setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnPerAppSettings).setOnClickListener { startActivity(Intent(this, PerAppActivity::class.java)) }
    }

    private fun initViews() {
        cbChinaDirect = findViewById(R.id.cbChinaDirect)
        cbLanDirect = findViewById(R.id.cbLanDirect)
        cbBlockAds = findViewById(R.id.cbBlockAds)
        cbSkipCertVerify = findViewById(R.id.cbSkipCertVerify)

        val groupsAdapter = RoutingGroupsAdapter(
            settings.routingGroups,
            move = { from, to ->
                if (to in settings.routingGroups.indices) {
                    val item = settings.routingGroups.removeAt(from)
                    settings.routingGroups.add(to, item)
                }
            },
            changed = { store.saveSettings(settings) }
        )
        findViewById<RecyclerView>(R.id.recyclerGroups).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = groupsAdapter
        }

        rulesAdapter = RoutingRulesAdapter(
            rules = settings.customRules,
            onEdit = { rule -> showRuleDialog(rule) },
            onDelete = { rule ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_rule_title)
                    .setMessage(getString(R.string.delete_rule_message, rule.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        settings.customRules.removeIf { it.id == rule.id }
                        store.saveSettings(settings)
                        rulesAdapter.notifyDataSetChanged()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        val rv = findViewById<RecyclerView>(R.id.recyclerRules)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = rulesAdapter
    }

    private fun bindImmediateChanges() {
        cbChinaDirect.isChecked = settings.chinaDirect
        cbLanDirect.isChecked = settings.lanDirect
        cbBlockAds.isChecked = settings.blockAds
        cbSkipCertVerify.isChecked = settings.skipCertVerify
        cbChinaDirect.setOnCheckedChangeListener { _, value -> settings.chinaDirect = value; store.saveSettings(settings) }
        cbLanDirect.setOnCheckedChangeListener { _, value -> settings.lanDirect = value; store.saveSettings(settings) }
        cbBlockAds.setOnCheckedChangeListener { _, value -> settings.blockAds = value; store.saveSettings(settings) }
        cbSkipCertVerify.setOnCheckedChangeListener { _, value -> settings.skipCertVerify = value; store.saveSettings(settings) }
    }

    private fun showRuleDialog(existing: RoutingRule?) {
        val view = layoutInflater.inflate(R.layout.dialog_routing_rule, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etRuleName)
        val spinnerMatchType = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerMatchType)
        val etPattern = view.findViewById<TextInputEditText>(R.id.etPattern)
        val spinnerAction = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerAction)

        spinnerMatchType.setAdapter(ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            MatchType.entries.map { it.localizedLabel(this) }
        ))
        spinnerAction.setAdapter(ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            RuleAction.entries.map { it.localizedLabel(this) }
        ))

        if (existing != null) {
            etName.setText(existing.name)
            spinnerMatchType.setText(existing.matchType.localizedLabel(this), false)
            etPattern.setText(existing.pattern)
            spinnerAction.setText(existing.action.localizedLabel(this), false)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_rule_title else R.string.edit_rule_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val pattern = etPattern.text.toString().trim()
                if (pattern.isBlank()) {
                    etPattern.error = getString(R.string.invalid_rule_pattern)
                    return@setOnClickListener
                }
                val matchType = MatchType.entries.find {
                    it.localizedLabel(this@SettingsActivity) == spinnerMatchType.text.toString()
                } ?: MatchType.DOMAIN_SUFFIX
                val action = RuleAction.entries.find {
                    it.localizedLabel(this@SettingsActivity) == spinnerAction.text.toString()
                } ?: RuleAction.PROXY
                if (!isValidRulePattern(matchType, pattern)) {
                    etPattern.error = getString(R.string.invalid_rule_pattern)
                    return@setOnClickListener
                }

                if (existing != null) {
                    existing.name = name.ifBlank { pattern }
                    existing.matchType = matchType
                    existing.pattern = pattern
                    existing.action = action
                } else {
                    settings.customRules.add(RoutingRule(
                        name = name.ifBlank { pattern },
                        matchType = matchType,
                        pattern = pattern,
                        action = action
                    ))
                }
                store.saveSettings(settings)
                rulesAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isValidRulePattern(type: MatchType, value: String): Boolean = when (type) {
        MatchType.IP_CIDR -> {
            val parts = value.split('/', limit = 2)
            val literal = parts.first()
            val address = literal.takeIf { it.matches(Regex("[0-9a-fA-F:.]+")) }
                ?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            address != null && prefix != null && prefix in 0..(address.address.size * 8)
        }
        MatchType.GEOSITE, MatchType.GEOIP -> value.removePrefix("geosite:").removePrefix("geoip:")
            .matches(Regex("[a-zA-Z0-9_-]+"))
        else -> !value.any(Char::isWhitespace)
    }

}

@Suppress("NotifyDataSetChanged")
class RoutingRulesAdapter(
    val rules: MutableList<RoutingRule>,
    private val onEdit: (RoutingRule) -> Unit,
    private val onDelete: (RoutingRule) -> Unit
) : RecyclerView.Adapter<RoutingRulesAdapter.VH>() {

    class VH(val binding: ItemConfigBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rule = rules[position]
        with(holder.binding) {
            radioSelected.visibility = View.GONE
            tvLatency.visibility = View.GONE
            tvName.text = rule.name
            tvInfo.text = root.context.getString(
                R.string.rule_summary,
                rule.matchType.localizedLabel(root.context),
                rule.pattern,
                rule.action.localizedLabel(root.context)
            )
            btnEdit.setOnClickListener { onEdit(rule) }
            btnDelete.setOnClickListener { onDelete(rule) }
        }
    }

    override fun getItemCount() = rules.size
}

@Suppress("NotifyDataSetChanged")
class RoutingGroupsAdapter(
    private val groups: MutableList<RoutingGroup>,
    private val move: (Int, Int) -> Unit,
    private val changed: () -> Unit
) : RecyclerView.Adapter<RoutingGroupsAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val enabled: SwitchMaterial = view.findViewById(R.id.enabled)
        val action: MaterialButton = view.findViewById(R.id.action)
        val up: ImageButton = view.findViewById(R.id.up)
        val down: ImageButton = view.findViewById(R.id.down)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_routing_group, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.text = group.localizedName(holder.itemView.context); holder.enabled.isChecked = group.enabled
        holder.enabled.setOnCheckedChangeListener { _, checked -> group.enabled = checked; changed() }
        holder.action.text = group.action.localizedLabel(holder.itemView.context)
        holder.action.setOnClickListener {
            group.action = when (group.action) { RuleAction.PROXY -> RuleAction.DIRECT; RuleAction.DIRECT -> RuleAction.BLOCK; RuleAction.BLOCK -> RuleAction.PROXY }
            changed()
            notifyItemChanged(position)
        }
        holder.up.isEnabled = position > 0; holder.down.isEnabled = position < groups.lastIndex
        holder.up.setOnClickListener { if (position > 0) { move(position, position - 1); changed(); notifyItemMoved(position, position - 1); notifyDataSetChanged() } }
        holder.down.setOnClickListener { if (position < groups.lastIndex) { move(position, position + 1); changed(); notifyItemMoved(position, position + 1); notifyDataSetChanged() } }
    }
    override fun getItemCount() = groups.size
}
