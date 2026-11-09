package su.xash.diffusion.model

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import su.xash.diffusion.MainActivity
import su.xash.diffusion.XashActivity
import java.io.File
import java.io.FileInputStream

class Game(val ctx: Context, val basedir: File) {
	private var iconName = "game.ico"
	var title = "Diffusion"
	var icon: Bitmap? = null
	var cover: Bitmap? = null

	var defaultGameDir = "diffusion"

	private val pref = ctx.getSharedPreferences(basedir.name, Context.MODE_PRIVATE)

	init {
		val gameInfo = File(basedir, "gameinfo.txt")
		if (gameInfo.exists()) {
			parseGameInfo(gameInfo)
		} else {
			val libListGam = File(basedir, "liblist.gam")
			if (libListGam.exists()) parseGameInfo(libListGam)
		}

		val iconFile = File(basedir, iconName)
		if (iconFile.exists()) {
			icon = BitmapFactory.decodeFile(iconFile.path)
		}

		try {
			cover = BackgroundBitmap.createBackground(basedir)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	fun startEngine(ctx: Context) {
		var commandLineArgs = ""
		if (basedir.name != defaultGameDir) {
			commandLineArgs += "-game ${basedir.name} "
		}
		commandLineArgs += pref.getString("arguments", "") ?: ""
		launchEngine(ctx, commandLineArgs.trim())
	}

	private fun launchEngine(
		ctx: Context,
		commandLineArgs: String
	) {
		val intent = Intent(ctx, XashActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			putExtra("gamedir", basedir.name)
			putExtra("argv", commandLineArgs)
			putExtra("usevolume", PreferenceManager.getDefaultSharedPreferences(ctx)
				.getBoolean("use_volume_buttons", false))
			putExtra("basedir", basedir.parent)
			putExtra("gamelibdir", ctx.applicationInfo.nativeLibraryDir)
			putExtra("package", ctx.packageName)
		}
		var activityContext = ctx
		while (activityContext is ContextWrapper && activityContext !is MainActivity) {
			val base = activityContext.baseContext
			if (base === activityContext) break
			activityContext = base
		}
		if (activityContext is MainActivity) {
			activityContext.launchWithMicrophonePermission { ctx.startActivity(intent) }
		} else {
			ctx.startActivity(intent)
		}
	}

	private fun parseGameInfo(file: File) {
		FileInputStream(file).use { inputStream ->
			inputStream.bufferedReader().use { reader ->
				reader.forEachLine {
					val tokens = it.split("\\s+".toRegex(), limit = 2)
					if (tokens.size >= 2) {
						val k = tokens[0]
						val v = tokens[1].trim('"')

						if (k == "title" || k == "game") title = v
						if (k == "icon") iconName = v
					}
				}
			}
		}
	}

	companion object {
		fun getGames(ctx: Context, file: File): List<Game> {
			val games = mutableListOf<Game>()

			if (checkIfGamedir(file)) {
				games.add(Game(ctx, file))
			} else {
				file.listFiles()?.forEach {
					if (it.isDirectory) {
						if (checkIfGamedir(it)) {
							games.add(Game(ctx, it))
						}
					}
				}
			}

			return games
		}

		fun checkIfGamedir(file: File): Boolean {
			if (File(file, "liblist.gam").exists()) return true
			if (File(file, "gameinfo.txt").exists()) return true

			return false
		}
	}
}
