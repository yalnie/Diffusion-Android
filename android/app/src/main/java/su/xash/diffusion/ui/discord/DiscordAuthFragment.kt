package su.xash.diffusion.ui.discord

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.diffusion.R
import su.xash.diffusion.discord.DiscordAuthManager
import su.xash.diffusion.discord.DiscordRpcService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

class DiscordAuthFragment : Fragment() {

    private lateinit var auth: DiscordAuthManager

    private lateinit var statusText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var progressBar: LinearProgressIndicator

    private lateinit var userCard: MaterialCardView
    private lateinit var userAvatar: ImageView
    private lateinit var userName: TextView
    private lateinit var userStatusBadge: TextView

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewTitle: TextView
    private lateinit var previewDetails: TextView
    private lateinit var previewState: TextView
    private lateinit var previewTimer: TextView

    private lateinit var connectButton: MaterialButton
    private lateinit var loginWebButton: MaterialButton
    private lateinit var logoutButton: MaterialButton

    private lateinit var tokenInput: TextInputEditText
    private lateinit var pasteTokenButton: MaterialButton
    private lateinit var saveTokenButton: MaterialButton
    private lateinit var autoConnectSwitch: SwitchMaterial

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        auth = DiscordAuthManager.get(requireContext())
        return inflater.inflate(R.layout.fragment_discord_auth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.authStatus)
        elapsedText = view.findViewById(R.id.authElapsed)
        progressBar = view.findViewById(R.id.authProgress)

        userCard = view.findViewById(R.id.userCard)
        userAvatar = view.findViewById(R.id.userAvatar)
        userName = view.findViewById(R.id.userName)
        userStatusBadge = view.findViewById(R.id.userStatusBadge)

        previewCard = view.findViewById(R.id.previewCard)
        previewTitle = view.findViewById(R.id.previewTitle)
        previewDetails = view.findViewById(R.id.previewDetails)
        previewState = view.findViewById(R.id.previewState)
        previewTimer = view.findViewById(R.id.previewTimer)

        connectButton = view.findViewById(R.id.connectButton)
        loginWebButton = view.findViewById(R.id.loginWebButton)
        logoutButton = view.findViewById(R.id.logoutButton)

        tokenInput = view.findViewById(R.id.tokenInput)
        pasteTokenButton = view.findViewById(R.id.pasteTokenButton)
        saveTokenButton = view.findViewById(R.id.saveTokenButton)
        autoConnectSwitch = view.findViewById(R.id.autoConnectSwitch)

        // Initialize values
        if (auth.hasToken()) {
            tokenInput.setText(auth.token)
        }
        autoConnectSwitch.isChecked = auth.isAutoConnectEnabled

        setupClickListeners()
        observeServiceState()
        startLiveTimerTicker()
        updateUserInfoDisplay()
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            if (DiscordRpcService.isRunning()) {
                DiscordRpcService.stop(requireContext())
            } else {
                if (!auth.hasToken()) {
                    Toast.makeText(requireContext(), R.string.discord_token_empty_error, Toast.LENGTH_SHORT).show()
                } else {
                    DiscordRpcService.start(requireContext())
                }
            }
        }

        loginWebButton.setOnClickListener {
            val dialog = LoginWebDialogFragment()
            dialog.setCallback(object : LoginWebDialogFragment.Callback {
                override fun onLoginSuccess(token: String) {
                    tokenInput.setText(token)
                    updateUserInfoDisplay()
                    DiscordRpcService.start(requireContext())
                }
            })
            dialog.show(parentFragmentManager, "DiscordLoginWebDialog")
        }

        logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.discord_logout_confirm_title)
                .setMessage(R.string.discord_logout_confirm_message)
                .setPositiveButton(R.string.discord_logout_button) { _, _ ->
                    DiscordRpcService.stop(requireContext())
                    auth.clearAuth()
                    tokenInput.setText("")
                    updateUserInfoDisplay()
                    Toast.makeText(requireContext(), R.string.discord_disconnected, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        pasteTokenButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteText = clip.getItemAt(0).text?.toString()?.trim()
                if (!pasteText.isNullOrEmpty()) {
                    tokenInput.setText(pasteText)
                    Toast.makeText(requireContext(), R.string.discord_token_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }

        saveTokenButton.setOnClickListener {
            val enteredToken = tokenInput.text?.toString()?.trim()
            if (enteredToken.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.discord_token_empty_error, Toast.LENGTH_SHORT).show()
            } else {
                auth.token = enteredToken
                Toast.makeText(requireContext(), R.string.discord_token_saved, Toast.LENGTH_SHORT).show()
                updateUserInfoDisplay()
                if (DiscordRpcService.isRunning()) {
                    DiscordRpcService.stop(requireContext())
                    view?.postDelayed({
                        DiscordRpcService.start(requireContext())
                    }, 500)
                } else {
                    DiscordRpcService.start(requireContext())
                }
            }
        }

        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            auth.isAutoConnectEnabled = isChecked
        }
    }

    private fun observeServiceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DiscordRpcService.statusFlow.collect { status ->
                        updateStatusUi(status)
                    }
                }
                launch {
                    DiscordRpcService.activityFlow.collect { act ->
                        updateActivityUi(act)
                    }
                }
                launch {
                    DiscordRpcService.errorMessageFlow.collect { err ->
                        if (!err.isNullOrBlank()) {
                            statusText.text = getString(R.string.discord_error, err)
                        }
                    }
                }
            }
        }
    }

    private fun updateStatusUi(status: DiscordRpcService.Status) {
        val hasToken = auth.hasToken()
        logoutButton.visibility = if (hasToken) View.VISIBLE else View.GONE

        when (status) {
            DiscordRpcService.Status.CONNECTED -> {
                statusText.text = getString(R.string.discord_connected)
                progressBar.visibility = View.GONE
                connectButton.text = getString(R.string.discord_disconnect_button)
                connectButton.setIconResource(R.drawable.ic_baseline_discord_24)
                updateUserInfoDisplay()
            }
            DiscordRpcService.Status.CONNECTING -> {
                statusText.text = getString(R.string.discord_connecting)
                progressBar.visibility = View.VISIBLE
                connectButton.text = getString(R.string.discord_disconnect_button)
            }
            DiscordRpcService.Status.DISCONNECTED -> {
                val displayName = auth.getDisplayName()
                if (hasToken && displayName != null) {
                    statusText.text = getString(R.string.discord_logged_in, displayName)
                } else {
                    statusText.text = getString(R.string.discord_not_logged_in)
                }
                progressBar.visibility = View.GONE
                connectButton.text = getString(R.string.discord_connect_button)
                connectButton.setIconResource(R.drawable.ic_baseline_discord_24)
                updateUserInfoDisplay()
            }
            DiscordRpcService.Status.ERROR -> {
                progressBar.visibility = View.GONE
                connectButton.text = getString(R.string.discord_connect_button)
            }
        }
    }

    private fun updateActivityUi(act: DiscordRpcService.ActivityInfo) {
        previewState.text = act.state
        if (!act.details.isNullOrBlank()) {
            previewDetails.text = act.details
            previewDetails.visibility = View.VISIBLE
        } else {
            previewDetails.visibility = View.GONE
        }
    }

    private fun updateUserInfoDisplay() {
        val name = auth.getDisplayName()
        if (name != null) {
            userCard.visibility = View.VISIBLE
            userName.text = name
            val isConnected = DiscordRpcService.statusFlow.value == DiscordRpcService.Status.CONNECTED
            userStatusBadge.text = if (isConnected) getString(R.string.discord_connected) else getString(R.string.discord_disconnected)
            userStatusBadge.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isConnected) R.color.discord_green else R.color.discord_subtext
                )
            )
            loadUserAvatar(auth.getAvatarUrl())
        } else {
            userCard.visibility = View.GONE
        }
    }

    private fun loadUserAvatar(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            userAvatar.setImageResource(R.drawable.ic_baseline_discord_24)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheFile = File(requireContext().cacheDir, "discord_avatar.png")
                val bitmap: Bitmap? = if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 3600000) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    val stream = URL(avatarUrl).openStream()
                    val b = BitmapFactory.decodeStream(stream)
                    stream.close()
                    if (b != null) {
                        val fos = FileOutputStream(cacheFile)
                        b.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        fos.close()
                    }
                    b
                }

                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            userAvatar.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        userAvatar.setImageResource(R.drawable.ic_baseline_discord_24)
                    }
                }
            }
        }
    }

    private fun startLiveTimerTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    val act = DiscordRpcService.activityFlow.value
                    val isRunning = DiscordRpcService.isRunning()
                    if (isRunning && act.startTimeMillis > 0) {
                        val elapsedSeconds = ((System.currentTimeMillis() - act.startTimeMillis) / 1000).coerceAtLeast(0)
                        val minutes = elapsedSeconds / 60
                        val seconds = elapsedSeconds % 60
                        val hours = minutes / 60
                        val timerText = if (hours > 0) {
                            String.format(Locale.US, "%02d:%02d:%02d elapsed", hours, minutes % 60, seconds)
                        } else {
                            String.format(Locale.US, "%02d:%02d elapsed", minutes, seconds)
                        }
                        previewTimer.text = timerText
                    } else {
                        previewTimer.text = "00:00 elapsed"
                    }
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserInfoDisplay()
        updateStatusUi(DiscordRpcService.statusFlow.value)
    }
}
