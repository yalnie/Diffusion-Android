package su.xash.diffusion.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import su.xash.diffusion.BuildConfig
import su.xash.diffusion.MainActivity
import su.xash.diffusion.R
import su.xash.diffusion.XashActivity
import su.xash.diffusion.databinding.FragmentLibraryBinding
import su.xash.diffusion.discord.DiscordRpcService
import su.xash.diffusion.model.DownloadService
import su.xash.diffusion.model.DownloadState
import su.xash.diffusion.model.GameDataDownloader
import su.xash.diffusion.ui.settings.AppSettingsPreferenceFragment
import su.xash.diffusion.util.Nomedia
import java.io.File

class LibraryFragment : Fragment(), MenuProvider {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private var isPermissionRequested = false
    private var hasShownFirstRunDialog = false
    private var downloadMenuItem: MenuItem? = null
    private var discordMenuItem: MenuItem? = null
    private var progressContainer: View? = null
    private var progressBar: LinearProgressIndicator? = null
    private var statusText: TextView? = null
    private var lastTerminalState: DownloadState? = null

    private val startActivityForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isPermissionRequested = false
            if (checkStoragePermissions()) {
                onStorageReady()
            }
        }

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            onStorageReady()
        }
    }

    private fun checkStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                if (isPermissionRequested) return false
                isPermissionRequested = true
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.file_access_required)
                    setMessage(R.string.file_access_message)
                    setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivityForResult.launch(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(
                                Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                            )
                        )
                    }
                    setNeutralButton(android.R.string.cancel) { _, _ ->
                        isPermissionRequested = false
                    }
                    setOnCancelListener {
                        isPermissionRequested = false
                    }
                    setCancelable(false)
                    show()

                    return false
                }
            } else {
                return true
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsNeeded = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsNeeded.isNotEmpty()) {
                if (isPermissionRequested) return false
                isPermissionRequested = true
                val showRationale = permissionsNeeded.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), it)
                }

                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.external_storage_required)
                    setMessage(R.string.external_storage_message)
                    if (showRationale) {
                        setPositiveButton(R.string.grant_permissions) { _, _ ->
                            requestPermissionLauncher.launch(permissionsNeeded)
                        }
                        setNegativeButton(android.R.string.cancel) { _, _ ->
                            isPermissionRequested = false
                        }
                    } else {
                        setPositiveButton(R.string.open_settings) { _, _ ->
                            startActivityForResult.launch(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                                    Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                                )
                            )
                        }
                        setNegativeButton(android.R.string.cancel) { _, _ ->
                            isPermissionRequested = false
                        }
                    }
                    setOnCancelListener {
                        isPermissionRequested = false
                    }
                    setCancelable(false)
                    show()
                }
                return false
            } else {
                return true
            }
        }
        return true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.findFragmentById(R.id.settingsFragment) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settingsFragment, AppSettingsPreferenceFragment())
                .commit()
        }

        binding.infoButton.setOnClickListener { showAboutDialog() }
        binding.runButton.setOnClickListener { startGame() }

        progressContainer = view.findViewById(R.id.downloadProgressContainer)
        progressBar = view.findViewById(R.id.downloaderProgress)
        statusText = view.findViewById(R.id.downloaderStatus)
        view.findViewById<View>(R.id.cancelButton)?.setOnClickListener { cancelDownload() }

        observeDownloadState()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useInternal = prefs.getBoolean("storage_toggle", true)
        if (useInternal || checkStoragePermissions()) {
            onStorageReady()
        }
    }

    private fun onStorageReady() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (!prefs.getBoolean("storage_toggle", true)) {
            Nomedia.ensureNomedia(libraryViewModel.getBaseDir())
        }
        checkFirstRunGameData()
    }

    private fun checkFirstRunGameData() {
        val downloader = GameDataDownloader(requireContext())
        if (downloader.hasCheckedFirstRun()) return
        if (hasShownFirstRunDialog) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useInternal = prefs.getBoolean("storage_toggle", true)
        if (!useInternal && !checkStoragePermissions()) return

        val basedir = libraryViewModel.getBaseDir()
        if (downloader.hasGameData(basedir)) {
            downloader.setFirstRunChecked()
            return
        }

        hasShownFirstRunDialog = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.no_game_data_found)
            .setMessage(R.string.no_game_data_message)
            .setPositiveButton(R.string.download_now) { _, _ ->
                downloader.setFirstRunChecked()
                startDiffusionDownload()
            }
            .setNegativeButton(R.string.no_thanks) { _, _ ->
                downloader.setFirstRunChecked()
            }
            .setCancelable(false)
            .show()
    }

    private fun startDiffusionDownload() {
        if (DownloadService.state.value is DownloadState.Downloading) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useInternal = prefs.getBoolean("storage_toggle", true)
        if (!useInternal && !checkStoragePermissions()) return

        val basedir = libraryViewModel.getBaseDir()
        val targetDir = File(basedir, "diffusion")

        val freeSpace = basedir.usableSpace
        if (freeSpace in 1 until 6_500_000_000L) {
            Toast.makeText(requireContext(), R.string.storage_space_low_message, Toast.LENGTH_LONG).show()
        }

        if (targetDir.isDirectory && targetDir.listFiles()?.isNotEmpty() == true) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.overwrite_confirm_title)
                .setMessage(R.string.overwrite_confirm_message)
                .setPositiveButton(R.string.overwrite) { _, _ -> beginDiffusionDownload() }
                .setNegativeButton(R.string.keep, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.install_diffusion_title)
                .setMessage(R.string.no_game_data_message)
                .setPositiveButton(android.R.string.yes) { _, _ -> beginDiffusionDownload() }
                .setNegativeButton(android.R.string.no, null)
                .show()
        }
    }

    private fun beginDiffusionDownload() {
        val basedir = libraryViewModel.getBaseDir()
        DownloadService.start(requireContext(), basedir)
    }

    private fun cancelDownload() {
        DownloadService.cancel(requireContext())
    }

    private fun observeDownloadState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.state.collect { state ->
                    when (state) {
                        is DownloadState.Idle -> {
                            lastTerminalState = null
                            progressContainer?.visibility = View.GONE
                            refreshDownloadAction()
                        }
                        is DownloadState.Downloading -> {
                            lastTerminalState = null
                            progressContainer?.visibility = View.VISIBLE
                            refreshDownloadAction()
                            if (state.total > 0) {
                                progressBar?.isIndeterminate = false
                                progressBar?.progress = ((state.current * 100L) / state.total).toInt()
                            } else {
                                progressBar?.isIndeterminate = true
                            }
                            statusText?.text = state.status
                        }
                        is DownloadState.Success -> {
                            if (lastTerminalState != state) {
                                lastTerminalState = state
                                progressContainer?.visibility = View.GONE
                                refreshDownloadAction()
                                DownloadService.consumeCompletion()
                                libraryViewModel.reloadGames(requireContext())
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.download_complete)
                                    .setMessage(R.string.download_complete_message)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        DownloadService.resetState()
                                    }
                                    .show()
                            }
                        }
                        is DownloadState.Error -> {
                            if (lastTerminalState != state) {
                                lastTerminalState = state
                                progressContainer?.visibility = View.GONE
                                refreshDownloadAction()
                                val errorMsg = state.message
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.download_failed)
                                    .setMessage(errorMsg)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        DownloadService.resetState()
                                    }
                                    .setNeutralButton("Copy") { _, _ ->
                                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Error", errorMsg)
                                        clipboard.setPrimaryClip(clip)
                                    }
                                    .show()
                            }
                        }
                        is DownloadState.Cancelled -> {
                            if (lastTerminalState != state) {
                                lastTerminalState = state
                                statusText?.text = getString(R.string.download_cancelled)
                                progressContainer?.postDelayed({
                                    progressContainer?.visibility = View.GONE
                                    refreshDownloadAction()
                                    DownloadService.resetState()
                                }, 1500)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_library, menu)
        downloadMenuItem = menu.findItem(R.id.action_download)
        discordMenuItem = menu.findItem(R.id.action_discord)
        refreshDownloadAction()
        refreshDiscordIcon()
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.action_download -> {
                startDiffusionDownload()
                return true
            }
            R.id.action_discord -> {
                findNavController().navigate(R.id.action_libraryFragment_to_discordAuthFragment)
                return true
            }
        }
        return false
    }

    private fun refreshDiscordIcon() {
        val item = discordMenuItem ?: return
        if (!isAdded) return
        val isRunning = DiscordRpcService.isRunning()
        requireActivity().runOnUiThread {
            val icon = item.icon?.mutate() ?: return@runOnUiThread
            if (isRunning) {
                DrawableCompat.setTint(icon, ContextCompat.getColor(requireContext(), R.color.discord_blurple))
            } else {
                DrawableCompat.setTintList(icon, null)
            }
            item.icon = icon
        }
    }

    private fun refreshDownloadAction() {
        val item = downloadMenuItem ?: return
        if (!isAdded) return
        val downloading = DownloadService.state.value is DownloadState.Downloading
        requireActivity().runOnUiThread {
            item.isEnabled = !downloading
            item.icon?.mutate()?.alpha = if (downloading) 100 else 255
        }
    }

    private fun startGame() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val useInternal = prefs.getBoolean("storage_toggle", true)
        if (!useInternal && !checkStoragePermissions()) {
            return
        }

        val basedir = libraryViewModel.getBaseDir()
        val downloader = GameDataDownloader(ctx)
        if (!downloader.hasGameData(basedir)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.no_game_data_found)
                .setMessage(R.string.no_game_data_message)
                .setPositiveButton(R.string.download_now) { _, _ ->
                    startDiffusionDownload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val gamePrefs = ctx.getSharedPreferences("diffusion", Context.MODE_PRIVATE)
        val commandLineArgs = gamePrefs.getString("arguments", "") ?: ""

        val gamedirFile = File(basedir, "diffusion")
        if (!gamedirFile.exists()) {
            gamedirFile.mkdirs()
        }

        val intent = Intent(ctx, XashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("gamedir", "diffusion")
            putExtra("argv", commandLineArgs)
            putExtra("usevolume", prefs.getBoolean("use_volume_buttons", false))
            putExtra("basedir", basedir.absolutePath)
            putExtra("gamelibdir", ctx.applicationInfo.nativeLibraryDir)
            putExtra("package", ctx.packageName)
        }
        (activity as? MainActivity)?.launchWithMicrophonePermission {
            startActivity(intent)
        } ?: startActivity(intent)
    }

    private fun showAboutDialog() {
        val message = TextView(requireContext()).apply {
            val content = "A first-person shooter by <a href='https://github.com/Aynekko'>Aynekko</a>"/* +
                    "<br><br>For any bug reports and feedback you can find me using links at the bottom or on Xash3D modding discord:<br>" +
                    "<a href='https://discord.gg/BxQUMUescJ'>https://discord.gg/BxQUMUescJ</a><br>" +
                    "Once there, use this link to get into dedicated Diffusion thread:<br>" +
                    "<a href='https://discord.com/channels/824538989616824350/1050151087195303996/threads/1061657457203609660'>https://discord.com/channels/824538989616824350/1050151087195303996/threads/1061657457203609660</a>" */

            text = Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(60, 40, 60, 0)
            textSize = 14f
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_title)
            .setView(message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("GitHub") { _, _ ->
                val url = "https://github.com/Aynekko/Diffusion"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshDownloadAction()
        refreshDiscordIcon()
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useInternal = prefs.getBoolean("storage_toggle", true)
        if (!useInternal) {
            if (checkStoragePermissions()) {
                onStorageReady()
            }
        } else {
            onStorageReady()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadMenuItem = null
        discordMenuItem = null
        progressContainer = null
        progressBar = null
        statusText = null
        _binding = null
    }
}
