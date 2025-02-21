/*
 * *************************************************************************
 *  PreferencesAdvanced.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.television.ui.preferences

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.*
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.*
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.MediaParsingService
import org.videolan.vlc.R
import org.videolan.vlc.gui.DebugLogActivity
import org.videolan.vlc.gui.dialogs.ConfirmDeleteDialog
import org.videolan.vlc.gui.dialogs.RenameDialog
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.getWritePermission
import org.videolan.vlc.util.FeatureFlag
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.deleteAllWatchNext
import java.io.File
import java.io.IOException

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
class PreferencesAdvanced : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener, CoroutineScope by MainScope() {
    override fun getXml(): Int {
        return R.xml.preferences_adv
    }


    override fun getTitleId(): Int {
        return R.string.advanced_prefs_category
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) findPreference<Preference>("debug_logs")?.isVisible = false
        if (FeatureFlag.values().isNotEmpty()) findPreference<Preference>("optional_features")?.isVisible = true
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val f = super.buildPreferenceDialogFragment(preference)
        if (f is CustomEditTextPreferenceDialogFragment) {
            when (preference.key) {
                "network_caching" -> {
                    f.setInputType(InputType.TYPE_CLASS_NUMBER)
                    f.setFilters(arrayOf(InputFilter.LengthFilter(5)))
                }
            }
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences
                .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val ctx = activity
        if (preference.key == null || ctx == null) return false
        when (preference.key) {
            "debug_logs" -> {
                val intent = Intent(ctx, DebugLogActivity::class.java)
                startActivity(intent)
                return true
            }
            "clear_history" -> {
                val dialog = ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_playback_history), description = getString(R.string.clear_history_message), buttonText = getString(R.string.clear_history))
                dialog.show((activity as FragmentActivity).supportFragmentManager, RenameDialog::class.simpleName)
                dialog.setListener {
                    Medialibrary.getInstance().clearHistory()
                    Settings.getInstance(activity).edit().remove(KEY_AUDIO_LAST_PLAYLIST).remove(KEY_MEDIA_LAST_PLAYLIST).apply()
                }
                return true
            }
            "clear_app_data" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    val dialog = ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_app_data), description = getString(R.string.clear_app_data_message), buttonText = getString(R.string.clear))
                    dialog.show((activity as FragmentActivity).supportFragmentManager, RenameDialog::class.simpleName)
                    dialog.setListener { (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() }
                } else {
                    val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.addCategory(Intent.CATEGORY_DEFAULT)
                    i.data = Uri.fromParts(SCHEME_PACKAGE, activity.applicationContext.packageName, null)
                    startActivity(i)
                }
                return true
            }
            "clear_media_db" -> {
                val medialibrary = Medialibrary.getInstance()
                if (medialibrary.isWorking) {
                    activity?.let {
                        Toast.makeText(
                            it,
                            R.string.settings_ml_block_scan,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    val dialog = ConfirmDeleteDialog.newInstance(
                        title = getString(R.string.clear_media_db),
                        description = getString(R.string.clear_media_db_message),
                        buttonText = getString(R.string.clear)
                    )
                    dialog.show(
                        (activity as FragmentActivity).supportFragmentManager,
                        RenameDialog::class.simpleName
                    )
                    dialog.setListener {
                        launch {
                            activity.stopService(Intent(activity, MediaParsingService::class.java))
                            withContext((Dispatchers.IO)) {
                                medialibrary.clearDatabase(false)
                                deleteAllWatchNext(activity)
                                //delete thumbnails
                                try {
                                    activity.getExternalFilesDir(null)?.let {
                                        val files =
                                            File(it.absolutePath + Medialibrary.MEDIALIB_FOLDER_NAME).listFiles()
                                        files?.forEach { file ->
                                            if (file.isFile) FileUtils.deleteFile(file)
                                        }
                                    }
                                    BitmapCache.clear()
                                } catch (e: IOException) {
                                    Log.e(this::class.java.simpleName, e.message, e)
                                }
                            }
                            medialibrary.discover(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)
                        }
                    }
                }
                return true
            }
            "quit_app" -> {
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }
            "dump_media_db" -> {
                if (Medialibrary.getInstance().isWorking)
                    activity?.let { Toast.makeText(it, R.string.settings_ml_block_scan, Toast.LENGTH_LONG).show() }
                else {
                    val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + Medialibrary.VLC_MEDIA_DB_NAME)
                    launch {
                        if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) {
                            val copied = withContext(Dispatchers.IO) {
                                val db = File(activity.getDir("db", Context.MODE_PRIVATE).toString() + Medialibrary.VLC_MEDIA_DB_NAME)

                                FileUtils.copyFile(db, dst)
                            }
                            Toast.makeText(activity, getString(if (copied) R.string.dump_db_succes else R.string.dump_db_failure), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                return true
            }
            "dump_app_db" -> {
                val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + ROOM_DATABASE)
                launch {
                    if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst))) {
                        val copied = withContext(Dispatchers.IO) {
                            val db = File((activity as FragmentActivity).getDir("db", Context.MODE_PRIVATE).parent!! + "/databases")

                            val files = db.listFiles()?.map { it.path }?.toTypedArray()

                            if (files == null) false else
                                FileUtils.zip(files, dst.path)

                        }
                        Toast.makeText(activity, getString(if (copied) R.string.dump_db_succes else R.string.dump_db_failure), Toast.LENGTH_LONG).show()
                    }
                }
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            "network_caching" -> {
                sharedPreferences.edit {
                    try {
                        val origValue = Integer.parseInt(sharedPreferences.getString(key, "0"))
                        val newValue = origValue.coerceIn(0, 60000)
                        putInt("network_caching_value", newValue)
                        findPreference<EditTextPreference>(key)?.let { it.text = newValue.toString() }
                        if (origValue != newValue) activity?.let { Toast.makeText(it, R.string.network_caching_popup, Toast.LENGTH_SHORT).show() }
                    } catch (e: NumberFormatException) {
                        putInt("network_caching_value", 0)
                        findPreference<EditTextPreference>(key)?.let { it.text = "0" }
                        activity?.let { Toast.makeText(it, R.string.network_caching_popup, Toast.LENGTH_SHORT).show() }
                    }
                }
                restartLibVLC()
            }
            "custom_libvlc_options" -> {
                try {
                    VLCInstance.restart()
                } catch (e: IllegalStateException){
                    activity?.let { Toast.makeText(it, R.string.custom_libvlc_options_invalid, Toast.LENGTH_LONG).show() }
                    sharedPreferences.putSingle("custom_libvlc_options", "")
                } finally {
                    (activity as? PreferencesActivity)?.restartMediaPlayer()
                }
                restartLibVLC()
            }
            "opengl", "deblocking", "enable_frame_skip", "enable_time_stretching_audio", "enable_verbose_mode", "prefer_smbv1" -> {
                restartLibVLC()
            }
        }
    }

    private fun restartLibVLC() {
        VLCInstance.restart()
        (activity as? PreferencesActivity)?.restartMediaPlayer()
    }
}
