package com.simoneluconi.gccamera.settings

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.simoneluconi.gccamera.R
import com.simoneluconi.gccamera.camera.CameraModel
import com.simoneluconi.gccamera.storage.StorageTarget
import java.io.File

/**
 * Everything that is not a shooting control lives here, off the viewfinder, grouped in tabs
 * along the top like a camera's own menu. Each tab is one nested screen of preferences.xml.
 */
class SettingsActivity : AppCompatActivity() {

    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        tab = savedInstanceState?.getInt(STATE_TAB) ?: 0
        val tabs = findViewById<TabLayout>(R.id.tabs)
        for ((_, title, icon) in TABS) tabs.addTab(tabs.newTab().setText(title).setIcon(icon), false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                show(tab)
            }

            override fun onTabUnselected(t: TabLayout.Tab) = Unit
            override fun onTabReselected(t: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(tab)?.select()
        if (savedInstanceState == null) show(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    private fun show(index: Int) {
        val f = SettingsFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, TABS[index].first) }
        }
        supportFragmentManager.beginTransaction().replace(R.id.content, f).commit()
    }

    companion object {
        private const val STATE_TAB = "tab"

        /** Nested screen key in preferences.xml, the tab's title and its icon. */
        private val TABS = listOf(
            Triple("tab_storage", R.string.tab_storage, R.drawable.ic_tab_storage),
            Triple("tab_image", R.string.tab_image, R.drawable.ic_tab_image),
            Triple("tab_display", R.string.tab_display, R.drawable.ic_tab_display),
            Triple("tab_sound", R.string.tab_sound, R.drawable.ic_tab_sound),
            Triple("tab_location", R.string.tab_location, R.drawable.ic_tab_location),
            Triple("tab_system", R.string.tab_system, R.drawable.ic_tab_system),
        )
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        private lateinit var prefs: Prefs

        private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                findPreference<SwitchPreferenceCompat>(Prefs.GEOTAG)?.isChecked = true
            } else {
                Toast.makeText(requireContext(), R.string.geotag_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = Prefs(requireContext())
            // The default folder is the model's name (GC100, GC200), so it cannot sit in the XML
            findPreference<EditTextPreference>(Prefs.FOLDER_NAME)?.let { if (it.text.isNullOrBlank()) it.text = prefs.folderName }

            findPreference<ListPreference>(Prefs.STORAGE_LOCATION)?.setOnPreferenceChangeListener { pref, value ->
                if (value != "internal") return@setOnPreferenceChangeListener true
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.storage_internal)
                    .setMessage(R.string.storage_internal_warning)
                    .setPositiveButton(R.string.storage_internal_confirm) { _, _ -> (pref as ListPreference).value = "internal" }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            }
            click(Prefs.FORMAT_SD) {
                // Formatting needs MOUNT_FORMAT_FILESYSTEMS (privileged): Android's own screen does it
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }
            click(Prefs.FILE_COUNTER_RESET) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.file_counter_reset_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.sp.edit().putInt(Prefs.FILE_COUNTER, 0).apply()
                        refreshSummaries()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            findPreference<SwitchPreferenceCompat>(Prefs.GEOTAG)?.setOnPreferenceChangeListener { _, value ->
                val granted = requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (value == true && !granted) {
                    locationPermission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                    false
                } else {
                    true
                }
            }
            findPreference<ListPreference>(Prefs.DEVELOP_WHEN)?.summaryProvider = Preference.SummaryProvider<ListPreference> {
                getString(R.string.pref_develop_when_summary, it.entry ?: "")
            }
            for (key in listOf(Prefs.ARTIST, Prefs.COPYRIGHT)) {
                findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    it.text?.takeIf(String::isNotBlank) ?: getString(R.string.not_set)
                }
            }
            click(Prefs.ANDROID_SETTINGS) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            click(Prefs.RESET) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.reset_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.sp.edit().clear().apply()
                        prefs.applyTheme()
                        requireActivity().recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            val version = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            val ispFirmware = runCatching { File("/sys/class/camera/rear/rear_camfw").readText().trim() }
                .getOrNull()?.ifEmpty { null } ?: getString(R.string.unknown_value)
            findPreference<Preference>(Prefs.ABOUT)?.summary = getString(R.string.about_summary, version, ispFirmware)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            refreshSummaries()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.THEME -> prefs.applyTheme()
                Prefs.STORAGE_LOCATION, Prefs.JPEG_ENGINE -> refreshSummaries()
            }
        }

        private fun refreshSummaries() {
            val ctx = requireContext()
            // When to develop only means something for our own JPEG
            findPreference<Preference>(Prefs.DEVELOP_WHEN)?.isVisible =
                prefs.sp.getString(Prefs.JPEG_ENGINE, "stock") == "ours"
            val t = StorageTarget(ctx).current(prefs.saveToInternal)
            val free = t?.freeBytes()
            findPreference<Preference>(Prefs.STORAGE_INFO)?.summary = if (free == null) {
                getString(R.string.storage_info_none)
            } else {
                // JPEG-only estimate here; the viewfinder adds the DNG when RAW is on
                getString(R.string.storage_info_summary, Formatter.formatShortFileSize(ctx, free), (free / prefs.averageShotBytes).toInt())
            }
            findPreference<Preference>(Prefs.FILE_COUNTER_RESET)?.summary =
                getString(R.string.pref_file_counter_summary, "${CameraModel.shortName}_%04d.jpg".format(prefs.peekFileNumber()))
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                action()
                true
            }
        }
    }
}
