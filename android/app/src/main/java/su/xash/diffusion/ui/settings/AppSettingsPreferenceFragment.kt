package su.xash.diffusion.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.diffusion.MainActivity
import su.xash.diffusion.R
import su.xash.diffusion.model.Game
import su.xash.diffusion.util.GameDataDirectory
import su.xash.diffusion.util.GameDataDirectory.Result
import java.io.File
import java.io.FileNotFoundException

class AppSettingsPreferenceFragment() : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var preferences: SharedPreferences
    private lateinit var gamePathPreference: GamePathPreference
    private lateinit var globalArgsPreference: Preference
    private lateinit var renderResolutionPreference: Preference
    private var storageTogglePreference: SwitchPreferenceCompat? = null

    private var gameDirectoryJob: Job? = null
    private var gameDirectoryRequestId = 0
    private var activeDialog: AlertDialog? = null

    private val gameDirectoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        handlePickedGameDirectory(uri)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            preferences.edit().putBoolean("storage_toggle", true).apply()
            storageTogglePreference?.isChecked = true
            updateGamePathSummary()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_preferences, rootKey)

        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferences.registerOnSharedPreferenceChangeListener(this)

        gamePathPreference = findPreference("game_path") ?: return
        globalArgsPreference = findPreference("global_arguments") ?: return
        renderResolutionPreference = findPreference("render_resolution") ?: return

        gamePathPreference.onResetClickListener = { resetGamePath() }

        storageTogglePreference = findPreference<SwitchPreferenceCompat>("storage_toggle")
        storageTogglePreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val targetValue = newValue as Boolean
                if (!targetValue) {
                    showExternalStorageWarning()
                    false
                } else {
                    preferences.edit().putBoolean("storage_toggle", true).apply()
                    storageTogglePreference?.isChecked = true
                    updateGamePathSummary()
                    false
                }
            }

        gamePathPreference.setOnPreferenceClickListener {
            val useInternalStorage = preferences.getBoolean("storage_toggle", true)
            if (useInternalStorage) {
                return@setOnPreferenceClickListener true
            }
            cancelPendingGameDirectorySelection()
            try {
                gameDirectoryPicker.launch(null)
            } catch (e: ActivityNotFoundException) {
                showGameDirectoryError(R.string.game_data_location_picker_unavailable)
            }
            true
        }

        globalArgsPreference.setOnPreferenceClickListener {
            showGlobalArgumentsDialog()
            true
        }

        renderResolutionPreference.setOnPreferenceClickListener {
            showRenderResolutionDialog()
            true
        }

        findPreference<Preference>("share_engine_log")?.setOnPreferenceClickListener {
            shareEngineLog(it)
            true
        }

        updateGamePathSummary()
        updateGlobalArgsSummary()
        updateRenderResolutionSummary()
    }

    private fun resetGamePath() {
        cancelPendingGameDirectorySelection()
        preferences.edit().remove("game_path").apply()
        updateGamePathSummary()
    }

    private fun handlePickedGameDirectory(uri: Uri) {
        cancelPendingGameDirectorySelection()
        val requestId = gameDirectoryRequestId
        val context = requireContext()

        gameDirectoryJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GameDataDirectory.resolve(context, uri)
            }
            if (requestId != gameDirectoryRequestId) return@launch

            val success = when (result) {
                is Result.Success -> result
                is Result.Failure -> {
                    showGameDirectoryError(result.messageRes, *result.formatArgs.toTypedArray())
                    return@launch
                }
            }

            val directory = success.directory

            val isSingleGame = withContext(Dispatchers.IO) {
                Game.checkIfGamedir(directory)
            }
            if (requestId != gameDirectoryRequestId) return@launch
            if (isSingleGame) {
                showGameDirectoryError(R.string.game_data_location_select_parent)
                return@launch
            }

            val hasGames = withContext(Dispatchers.IO) { hasGameDirectories(directory) }
            if (requestId != gameDirectoryRequestId) return@launch

            when (hasGames) {
                null -> showGameDirectoryError(R.string.game_data_location_unreadable)
                true -> saveGamePath(directory, requestId)
                false -> confirmGamePathWithoutGames(directory, requestId)
            }
        }
    }

    private fun hasGameDirectories(directory: File): Boolean? = try {
        directory.listFiles()?.any { it.isDirectory && Game.checkIfGamedir(it) }
    } catch (e: SecurityException) {
        null
    }

    private fun cancelPendingGameDirectorySelection() {
        gameDirectoryRequestId++
        gameDirectoryJob?.cancel()
        gameDirectoryJob = null
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun saveGamePath(directory: File, requestId: Int) {
        if (requestId != gameDirectoryRequestId) return

        val defaultExternal = Environment.getExternalStorageDirectory().absolutePath + "/xash"
        if (directory.absolutePath == defaultExternal) {
            preferences.edit().remove("game_path").apply()
        } else {
            preferences.edit().putString("game_path", directory.absolutePath).apply()
        }

        updateGamePathSummary()
    }

    private fun confirmGamePathWithoutGames(directory: File, requestId: Int) {
        if (requestId != gameDirectoryRequestId) return
        showDialog(
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.game_data_location_empty_title)
                .setMessage(R.string.game_data_location_empty_message)
                .setPositiveButton(R.string.game_data_location_use_anyway) { _, _ ->
                    saveGamePath(directory, requestId)
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showGameDirectoryError(messageRes: Int, vararg formatArgs: Any) {
        val message = if (formatArgs.isEmpty()) {
            getString(messageRes)
        } else {
            getString(messageRes, *formatArgs)
        }

        showDialog(
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.game_data_location)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        )
    }

    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        activeDialog?.dismiss()

        val dialog = builder.create()
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun shareEngineLog(preference: Preference) {
        val context = requireContext()
        val storagePath = (activity as? MainActivity)?.getStoragePath() ?: return
        preference.isEnabled = false
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val source = File(storagePath, "engine.log")
                    if (!source.isFile) throw FileNotFoundException()
                    val directory = File(context.cacheDir, "shared_logs").apply { mkdirs() }
                    val snapshot = File(directory, "engine.log")
                    source.copyTo(snapshot, overwrite = true)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", snapshot)
                }
                if (!isAdded) return@launch
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("engine.log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_engine_log)))
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isAdded) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.share_engine_log)
                        .setMessage(if (error is FileNotFoundException) R.string.engine_log_not_found else R.string.engine_log_share_failed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                preference.isEnabled = true
            }
        }
    }

    private fun showExternalStorageWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.external_storage_warning_title)
            .setMessage(R.string.external_storage_warning_message)
            .setPositiveButton(R.string.external_storage_warning_ok) { _, _ ->
                preferences.edit().putBoolean("storage_toggle", false).apply()
                storageTogglePreference?.isChecked = false
                updateGamePathSummary()
                checkStoragePermissions()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                preferences.edit().putBoolean("storage_toggle", true).apply()
                storageTogglePreference?.isChecked = true
                updateGamePathSummary()
            }
            .setOnCancelListener {
                preferences.edit().putBoolean("storage_toggle", true).apply()
                storageTogglePreference?.isChecked = true
                updateGamePathSummary()
            }
            .show()
    }

    private fun checkStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.file_access_required)
                    setMessage(R.string.file_access_message)
                    setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.fromParts("package", requireContext().packageName, null)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    setNeutralButton(android.R.string.cancel) { _, _ ->
                        preferences.edit().putBoolean("storage_toggle", true).apply()
                        storageTogglePreference?.isChecked = true
                        updateGamePathSummary()
                    }
                    setOnCancelListener {
                        preferences.edit().putBoolean("storage_toggle", true).apply()
                        storageTogglePreference?.isChecked = true
                        updateGamePathSummary()
                    }
                    show()
                }
                return false
            } else {
                return true
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsNeeded = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsNeeded.isNotEmpty()) {
                requestPermissionLauncher.launch(permissionsNeeded)
                return false
            }
        }
        return true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "storage_toggle" -> {
                updateGamePathSummary()
            }
            "game_path" -> {
                updateGamePathSummary()
            }
            "global_arguments" -> {
                updateGlobalArgsSummary()
            }
            "render_resolution" -> {
                updateRenderResolutionSummary()
            }
        }
    }

    private fun updateGamePathSummary() {
        val useInternalStorage = preferences.getBoolean("storage_toggle", true)
        gamePathPreference.isEnabled = !useInternalStorage
        if (useInternalStorage) {
            val internalPath = context?.getExternalFilesDir(null)?.absolutePath ?: "Android/data/su.xash.diffusion/files"
            gamePathPreference.summary = "Internal Storage ($internalPath)"
            gamePathPreference.isResetEnabled = false
        } else {
            val hasCustomPath = preferences.contains("game_path")
            val externalPath = preferences.getString("game_path", null)
                ?: (Environment.getExternalStorageDirectory().absolutePath + "/xash")
            gamePathPreference.summary = "External Storage ($externalPath)"
            gamePathPreference.isResetEnabled = hasCustomPath
        }
    }

    private fun updateGlobalArgsSummary() {
        val globalArgs = preferences.getString("global_arguments", getString(R.string.default_global_arguments))
        if (globalArgs.isNullOrEmpty()) {
            globalArgsPreference.summary = "No global arguments set"
        } else {
            globalArgsPreference.summary = globalArgs
        }
    }

    private fun updateRenderResolutionSummary() {
        val resolution = preferences.getString("render_resolution", "")
        if (resolution.isNullOrEmpty()) {
            renderResolutionPreference.summary = getString(R.string.render_resolution_summary)
        } else {
            renderResolutionPreference.summary = resolution
        }
    }

    private fun showGlobalArgumentsDialog() {
        val currentArgs = preferences.getString("global_arguments", getString(R.string.default_global_arguments)) ?: ""
        
        val editText = EditText(requireContext())
        editText.setText(currentArgs)
        editText.hint = "e.g., -dev -log"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.global_command_line)
            .setMessage(R.string.global_command_line_summary)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newArgs = editText.text.toString().trim()
                preferences.edit().putString("global_arguments", newArgs).apply()
                updateGlobalArgsSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                preferences.edit().putString("global_arguments", "").apply()
                updateGlobalArgsSummary()
            }
            .show()
    }

    private fun showRenderResolutionDialog() {
        val currentResolution = preferences.getString("render_resolution", "") ?: ""

        val editText = EditText(requireContext())
        editText.setText(currentResolution)
        editText.hint = "e.g., 640x480"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.render_resolution_dialog)
            .setMessage(R.string.render_resolution_dialog_summary)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newResolution = editText.text.toString().trim()
                preferences.edit().putString("render_resolution", newResolution).apply()
                updateRenderResolutionSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                preferences.edit().putString("render_resolution", "").apply()
                updateRenderResolutionSummary()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(this)
        val useInternal = preferences.getBoolean("storage_toggle", true)
        if (!useInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            preferences.edit().putBoolean("storage_toggle", true).apply()
        }
        storageTogglePreference?.isChecked = preferences.getBoolean("storage_toggle", true)
        updateGamePathSummary()
        updateGlobalArgsSummary()
        updateRenderResolutionSummary()
    }

    override fun onPause() {
        super.onPause()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        cancelPendingGameDirectorySelection()
        super.onDestroyView()
    }
}
