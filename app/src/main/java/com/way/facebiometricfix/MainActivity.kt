package com.way.facebiometricfix

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val info: ApplicationInfo,
    ) {
        var icon: Drawable? = null
    }

    private lateinit var statusView: TextView
    private lateinit var defaultAllowSwitch: Switch
    private lateinit var searchField: EditText
    private lateinit var showSystemBox: CheckBox
    private lateinit var loadingView: ProgressBar
    private lateinit var listView: ListView

    private val adapter = AppAdapter()
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()
    private val iconLoader: ExecutorService = Executors.newFixedThreadPool(2)

    private var prefs: SharedPreferences? = null
    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()
    private var updatingUi = false

    private val serviceObserver: (XposedService?) -> Unit = { onServiceChanged(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        defaultAllowSwitch = findViewById(R.id.default_allow)
        searchField = findViewById(R.id.search)
        showSystemBox = findViewById(R.id.show_system)
        loadingView = findViewById(R.id.loading)
        listView = findViewById(R.id.apps)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> toggle(shownApps[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            reset(shownApps[position])
            true
        }

        defaultAllowSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            save { FacePolicy.writeDefaultAllow(it, checked) }
            adapter.notifyDataSetChanged()
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        showSystemBox.setOnCheckedChangeListener { _, _ -> applyFilter() }

        ModuleApp.observe(serviceObserver)
        loadApps()
    }

    override fun onDestroy() {
        ModuleApp.stopObserving(serviceObserver)
        loader.shutdownNow()
        iconLoader.shutdownNow()
        super.onDestroy()
    }

    private fun onServiceChanged(service: XposedService?) {
        prefs = service?.let {
            runCatching { it.getRemotePreferences(FacePolicy.GROUP) }
                .onFailure { e -> Log.e(TAG, "Can't open remote preferences", e) }
                .getOrNull()
        }

        statusView.text = when {
            service == null -> getString(R.string.status_not_connected)
            prefs == null -> getString(R.string.status_no_prefs)
            else -> getString(
                R.string.status_connected,
                service.frameworkName,
                service.frameworkVersion,
            )
        }

        val editable = prefs != null
        defaultAllowSwitch.isEnabled = editable
        listView.isEnabled = editable
        updatingUi = true
        defaultAllowSwitch.isChecked = FacePolicy.defaultAllow(prefs)
        updatingUi = false
        adapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        val pm = packageManager
        val ownPackage = packageName
        loader.execute {
            val apps = pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != ownPackage }
                .map {
                    AppEntry(
                        packageName = it.packageName,
                        label = it.loadLabel(pm).toString(),
                        isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        info = it,
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                loadingView.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchField.text.toString().trim()
        val showSystem = showSystemBox.isChecked
        shownApps = allApps.filter { app ->
            // Always show apps with an explicit setting, so they can't get lost.
            val visible = showSystem || !app.isSystem ||
                FacePolicy.setting(prefs, app.packageName) != FacePolicy.Setting.DEFAULT
            visible && (query.isEmpty() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
        }
        adapter.notifyDataSetChanged()
    }

    /** Flips whether the app gets face unlock; matching the default clears the explicit setting. */
    private fun toggle(app: AppEntry) {
        save { prefs ->
            val nowAllowed = !FacePolicy.isAllowed(prefs, app.packageName)
            val setting = when {
                nowAllowed == FacePolicy.defaultAllow(prefs) -> FacePolicy.Setting.DEFAULT
                nowAllowed -> FacePolicy.Setting.ALLOWED
                else -> FacePolicy.Setting.BLOCKED
            }
            FacePolicy.write(prefs, app.packageName, setting)
        }
        adapter.notifyDataSetChanged()
    }

    private fun reset(app: AppEntry) {
        if (FacePolicy.setting(prefs, app.packageName) == FacePolicy.Setting.DEFAULT) return
        save { FacePolicy.write(it, app.packageName, FacePolicy.Setting.DEFAULT) }
        adapter.notifyDataSetChanged()
        Toast.makeText(this, getString(R.string.reset_done, app.label), Toast.LENGTH_SHORT).show()
    }

    private fun save(block: (SharedPreferences) -> Unit) {
        val prefs = prefs
        if (prefs == null) {
            Toast.makeText(this, R.string.status_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { block(prefs) }.onFailure {
            Log.e(TAG, "Can't save policy", it)
            Toast.makeText(this, getString(R.string.save_failed, it.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shownApps.size
        override fun getItem(position: Int) = shownApps[position]
        override fun getItemId(position: Int) = shownApps[position].packageName.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = shownApps[position]
            val allowed = FacePolicy.isAllowed(prefs, app.packageName)

            view.findViewById<TextView>(R.id.label).text = app.label
            view.findViewById<TextView>(R.id.package_name).text = app.packageName
            view.findViewById<TextView>(R.id.setting).setText(
                when (FacePolicy.setting(prefs, app.packageName)) {
                    FacePolicy.Setting.DEFAULT -> R.string.setting_default
                    FacePolicy.Setting.ALLOWED -> R.string.setting_allowed
                    FacePolicy.Setting.BLOCKED -> R.string.setting_blocked
                }
            )
            view.findViewById<Switch>(R.id.toggle).apply {
                isChecked = allowed
                isEnabled = prefs != null
            }

            val iconView = view.findViewById<ImageView>(R.id.icon)
            iconView.tag = app.packageName
            val icon = app.icon
            if (icon != null) {
                iconView.setImageDrawable(icon)
            } else {
                iconView.setImageDrawable(null)
                val pm = packageManager
                iconLoader.execute {
                    val loaded = runCatching { app.info.loadIcon(pm) }.getOrNull() ?: return@execute
                    app.icon = loaded
                    iconView.post {
                        if (iconView.tag == app.packageName) iconView.setImageDrawable(loaded)
                    }
                }
            }
            return view
        }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"
    }
}
