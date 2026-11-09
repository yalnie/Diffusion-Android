package su.xash.diffusion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.preference.PreferenceManager
import su.xash.diffusion.databinding.ActivityMainBinding
import su.xash.diffusion.util.CrashReports
import su.xash.diffusion.util.monospaceTextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private var pendingMicrophoneLaunch: (() -> Unit)? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val launch = pendingMicrophoneLaunch
        pendingMicrophoneLaunch = null
        if (!granted) {
            Toast.makeText(this, R.string.microphone_permission_denied, Toast.LENGTH_LONG).show()
        }
        if (launch != null && !isFinishing && !isDestroyed) {
            launch()
        }
    }

    fun launchWithMicrophonePermission(launch: () -> Unit) {
        if (pendingMicrophoneLaunch != null) return
        val enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("microphone", true)
        if (!enabled || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launch()
        } else {
            pendingMicrophoneLaunch = launch
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (!preferences.contains("use_volume_buttons")) {
            val gamePreferences = getSharedPreferences("diffusion", MODE_PRIVATE)
            preferences.edit().putBoolean("use_volume_buttons",
                gamePreferences.getBoolean("use_volume_buttons", false)).apply()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        CrashReports.prune(this)
        showPendingCrashReport()
    }

    fun getStoragePath(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useInternal = prefs.getBoolean("storage_toggle", true)
        return if (useInternal) {
            getExternalFilesDir(null)?.absolutePath ?: (android.os.Environment.getExternalStorageDirectory().absolutePath + "/Android/data/" + packageName + "/files")
        } else {
            prefs.getString("game_path", null) ?: (android.os.Environment.getExternalStorageDirectory().absolutePath + "/xash")
        }
    }

    fun getStorageSummary(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useInternal = prefs.getBoolean("storage_toggle", true)
        return if (useInternal) {
            val internalPath = getExternalFilesDir(null)?.absolutePath ?: "Android/data/$packageName/files"
            "Internal Storage ($internalPath)"
        } else {
            val externalPath = prefs.getString("game_path", null)
                ?: (android.os.Environment.getExternalStorageDirectory().absolutePath + "/xash")
            "External Storage ($externalPath)"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun showPendingCrashReport() {
        val pending = CrashReports.pendingStacktrace(this)
        if (!pending.exists() || pending.length() == 0L)
            return

        val historyDir = CrashReports.historyDir(this).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val entryDir = File(historyDir, "crash-$ts").apply { mkdirs() }

        moveOrCopy(pending, File(entryDir, CrashReports.STACKTRACE_NAME))
        moveOrCopy(CrashReports.pendingSysinfo(this), File(entryDir, CrashReports.SYSINFO_NAME))
        moveOrCopy(CrashReports.pendingIntent(this), File(entryDir, CrashReports.INTENT_NAME))
        moveOrCopy(CrashReports.pendingEngineLog(this), File(entryDir, CrashReports.ENGINELOG_NAME))

        val entry = CrashReports.Entry(entryDir)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_dialog_title)
            .setView(monospaceTextView(this, entry.summary()))
            .setPositiveButton(R.string.crash_send_to_developers) { _, _ -> CrashReports.sendByEmail(this, entry) }
            .setNeutralButton(R.string.crash_share) { _, _ -> CrashReports.share(this, entry) }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

    private fun moveOrCopy(src: File, dst: File) {
        if (!src.exists())
            return

        if (src.renameTo(dst))
            return

        src.copyTo(dst, overwrite = true)
        src.delete()
    }
}
