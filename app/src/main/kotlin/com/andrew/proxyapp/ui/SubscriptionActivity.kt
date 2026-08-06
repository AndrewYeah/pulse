package com.andrew.proxyapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.Subscription
import com.andrew.proxyapp.data.SubscriptionManager
import com.andrew.proxyapp.databinding.ItemSubscriptionBinding
import kotlinx.coroutines.launch
import java.net.URL

@Suppress("NotifyDataSetChanged")
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var adapter: SubscriptionAdapter

    private val updatingIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        configureSystemBars()

        store = ConfigStore.get(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        adapter = SubscriptionAdapter(
            subscriptions = store.getSubscriptions(),
            onUpdate = { sub -> updateSubscription(sub) },
            onEdit = { sub -> showEditDialog(sub) },
            onDelete = { sub ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_subscription_title)
                    .setMessage(getString(R.string.delete_subscription_message, sub.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val oldNodes = store.getConfigsBySubscription(sub.id)
                        store.deleteSubscription(sub.id)
                        refreshList()
                        setResult(RESULT_OK)
                        Snackbar.make(findViewById(R.id.recyclerView), getString(R.string.deleted_subscription, sub.name), Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo) {
                                if (oldNodes.isEmpty()) store.saveSubscription(sub)
                                else store.replaceSubscriptionConfigs(sub, oldNodes)
                                refreshList()
                            }.show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showEditDialog(null)
        }

        findViewById<MaterialButton>(R.id.btnUpdateAll).setOnClickListener {
            val subs = store.getSubscriptions()
            if (subs.isEmpty()) {
                Toast.makeText(this, R.string.no_subscriptions_hint, Toast.LENGTH_SHORT).show()
            } else {
                updateAll(subs)
            }
        }
    }

    private fun showEditDialog(existing: Subscription?) {
        val view = layoutInflater.inflate(R.layout.dialog_subscription, null)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvDialogTitle)
        val etName = view.findViewById<TextInputEditText>(R.id.etSubName)
        val etUrl = view.findViewById<TextInputEditText>(R.id.etSubUrl)

        if (existing != null) {
            tvTitle.setText(R.string.edit_subscription)
            etName.setText(existing.name)
            etUrl.setText(existing.url)
        } else {
            tvTitle.setText(R.string.new_subscription)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val valid = runCatching { URL(url) }.getOrNull()
                if (valid == null || !valid.protocol.equals("https", true) || valid.host.isBlank()) {
                    etUrl.error = getString(R.string.subscription_https_required)
                    return@setOnClickListener
                }
                val sub = existing ?: Subscription()
                sub.name = name.ifBlank {
                    getString(R.string.subscription_default_name, (System.currentTimeMillis() % 10000).toInt())
                }
                sub.url = url
                store.saveSubscription(sub)
                refreshList()
                dialog.dismiss()
                updateSubscription(sub)
            }
        }
        dialog.show()
    }

    private fun updateSubscription(sub: Subscription) {
        if (!updatingIds.add(sub.id)) return
        adapter.updatingIds = updatingIds
        adapter.notifyDataSetChanged()
        Toast.makeText(this, getString(R.string.updating_subscription, sub.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val count = SubscriptionManager.update(sub, store)
                refreshList()
                setResult(RESULT_OK)
                Toast.makeText(
                    this@SubscriptionActivity,
                    getString(R.string.subscription_update_success, sub.name, count),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                refreshList()
                Toast.makeText(
                    this@SubscriptionActivity,
                    getString(R.string.subscription_update_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                updatingIds.remove(sub.id)
                adapter.notifyDataSetChanged()
            }
        }
        refreshList()
    }

    private fun updateAll(subs: List<Subscription>) {
        val button = findViewById<MaterialButton>(R.id.btnUpdateAll)
        button.isEnabled = false
        lifecycleScope.launch {
            var success = 0
            subs.forEachIndexed { index, sub ->
                updatingIds.add(sub.id); adapter.updatingIds = updatingIds; adapter.notifyDataSetChanged()
                button.text = getString(R.string.progress_count, index + 1, subs.size)
                runCatching { SubscriptionManager.update(sub, store) }.onSuccess { success++ }
                updatingIds.remove(sub.id); refreshList()
            }
            button.isEnabled = true; button.setText(R.string.update_all)
            setResult(RESULT_OK)
            Snackbar.make(button, getString(R.string.subscriptions_update_summary, success, subs.size), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun refreshList() {
        adapter.subscriptions = store.getSubscriptions()
        adapter.notifyDataSetChanged()
        findViewById<android.widget.TextView>(R.id.emptyView).visibility =
            if (adapter.subscriptions.isEmpty()) View.VISIBLE else View.GONE
    }
}

@Suppress("NotifyDataSetChanged")
class SubscriptionAdapter(
    var subscriptions: MutableList<Subscription>,
    private val onUpdate: (Subscription) -> Unit,
    private val onEdit: (Subscription) -> Unit,
    private val onDelete: (Subscription) -> Unit
) : RecyclerView.Adapter<SubscriptionAdapter.VH>() {

    var updatingIds: Set<String> = emptySet()

    class VH(val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubscriptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sub = subscriptions[position]
        with(holder.binding) {
            tvSubName.text = sub.name.ifBlank { holder.itemView.context.getString(R.string.unnamed_subscription) }
            tvSubInfo.text = holder.itemView.context.getString(
                R.string.subscription_summary,
                sub.localizedSummary(holder.itemView.context),
                sub.localizedLastUpdated(holder.itemView.context)
            )

            if (sub.lastError.isNotBlank()) {
                tvSubInfo.text = holder.itemView.context.getString(R.string.subscription_error, tvSubInfo.text, sub.lastError)
            }
            btnUpdate.isEnabled = sub.id !in updatingIds
            btnUpdate.alpha = if (btnUpdate.isEnabled) 1f else 0.4f
            btnUpdate.setOnClickListener { onUpdate(sub) }
            btnEdit.setOnClickListener { onEdit(sub) }
            btnDelete.setOnClickListener { onDelete(sub) }
        }
    }

    override fun getItemCount() = subscriptions.size
}
