package su.xash.diffusion.discord

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.preference.PreferenceManager
import java.io.File

class DiscordAuthManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val PREF_DISCORD_TOKEN = "discord_token"
        const val PREF_DISCORD_USER_ID = "discord_user_id"
        const val PREF_DISCORD_USERNAME = "discord_username"
        const val PREF_DISCORD_DISCRIMINATOR = "discord_discriminator"
        const val PREF_DISCORD_AVATAR = "discord_avatar"
        const val PREF_DISCORD_AUTO_CONNECT = "discord_auto_connect"

        @Volatile
        private var instance: DiscordAuthManager? = null

        fun get(context: Context): DiscordAuthManager {
            return instance ?: synchronized(this) {
                instance ?: DiscordAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var token: String?
        get() = prefs.getString(PREF_DISCORD_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(PREF_DISCORD_TOKEN)
                } else {
                    putString(PREF_DISCORD_TOKEN, value.trim())
                }
            }.apply()
        }

    var userId: String?
        get() = prefs.getString(PREF_DISCORD_USER_ID, null)
        private set(value) {
            prefs.edit().putString(PREF_DISCORD_USER_ID, value).apply()
        }

    var username: String?
        get() = prefs.getString(PREF_DISCORD_USERNAME, null)
        private set(value) {
            prefs.edit().putString(PREF_DISCORD_USERNAME, value).apply()
        }

    var discriminator: String?
        get() = prefs.getString(PREF_DISCORD_DISCRIMINATOR, null)
        private set(value) {
            prefs.edit().putString(PREF_DISCORD_DISCRIMINATOR, value).apply()
        }

    var avatar: String?
        get() = prefs.getString(PREF_DISCORD_AVATAR, null)
        private set(value) {
            prefs.edit().putString(PREF_DISCORD_AVATAR, value).apply()
        }

    var isAutoConnectEnabled: Boolean
        get() = prefs.getBoolean(PREF_DISCORD_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(PREF_DISCORD_AUTO_CONNECT, value).apply()

    fun hasToken(): Boolean = !token.isNullOrBlank()

    fun saveUser(id: String?, name: String?, disc: String?, avatarHash: String?) {
        prefs.edit().apply {
            putString(PREF_DISCORD_USER_ID, id)
            putString(PREF_DISCORD_USERNAME, name)
            putString(PREF_DISCORD_DISCRIMINATOR, disc)
            putString(PREF_DISCORD_AVATAR, avatarHash)
        }.apply()
    }

    fun getDisplayName(): String? {
        val u = username ?: return null
        val d = discriminator
        return if (!d.isNullOrEmpty() && d != "0") {
            "$u#$d"
        } else {
            "@$u"
        }
    }

    fun getAvatarUrl(): String? {
        val uId = userId
        val av = avatar
        return if (!uId.isNullOrEmpty() && !av.isNullOrEmpty()) {
            "https://cdn.discordapp.com/avatars/$uId/$av.png"
        } else if (!discriminator.isNullOrEmpty()) {
            val discNum = discriminator?.toIntOrNull() ?: 0
            "https://cdn.discordapp.com/embed/avatars/${discNum % 5}.png"
        } else {
            null
        }
    }

    fun clearAuth() {
        prefs.edit().apply {
            remove(PREF_DISCORD_TOKEN)
            remove(PREF_DISCORD_USER_ID)
            remove(PREF_DISCORD_USERNAME)
            remove(PREF_DISCORD_DISCRIMINATOR)
            remove(PREF_DISCORD_AVATAR)
        }.apply()

        // Clean up WebView storage and cookies
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()

            val appWebView = File(context.filesDir.parentFile, "app_webview")
            if (appWebView.exists()) {
                appWebView.deleteRecursively()
            }
        } catch (_: Throwable) {}
    }

    fun extractTokenFromLevelDb(): String? {
        return try {
            val webviewDir = File(context.filesDir.parentFile, "app_webview/Default/Local Storage/leveldb")
            if (!webviewDir.exists() || !webviewDir.isDirectory) return null
            val files = webviewDir.listFiles { _, name ->
                name.endsWith(".log") || name.endsWith(".ldb")
            } ?: return null

            files.sortByDescending { it.lastModified() }
            for (f in files) {
                val content = f.readBytes().toString(Charsets.ISO_8859_1)
                val tokenKey = "\"token\""
                var idx = 0
                while (true) {
                    idx = content.indexOf(tokenKey, idx)
                    if (idx == -1) break
                    val after = content.substring(idx + tokenKey.length)
                    val quote1 = after.indexOf('"')
                    if (quote1 != -1) {
                        val quote2 = after.indexOf('"', quote1 + 1)
                        if (quote2 != -1) {
                            val candidate = after.substring(quote1 + 1, quote2)
                            if (candidate.length >= 50 && !candidate.contains(" ") && !candidate.contains("\\")) {
                                return candidate
                            }
                        }
                    }
                    idx += tokenKey.length
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
