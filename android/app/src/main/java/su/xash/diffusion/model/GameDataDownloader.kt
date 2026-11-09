package su.xash.diffusion.model

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import su.xash.diffusion.util.Nomedia
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class GameDataDownloader(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasGameData(basedir: File): Boolean {
        val gameDir = File(basedir, GAMEDIR)
        if (!gameDir.isDirectory) return false
        val gameInfo = File(gameDir, "gameinfo.txt")
        val liblist = File(gameDir, "liblist.gam")
        val resPk3 = File(gameDir, "res0.pk3")
        return gameInfo.exists() || liblist.exists() || resPk3.exists()
    }

    fun hasCheckedFirstRun(): Boolean {
        return prefs.getBoolean(PREF_FIRST_RUN_CHECKED, false)
    }

    fun setFirstRunChecked() {
        prefs.edit().putBoolean(PREF_FIRST_RUN_CHECKED, true).apply()
    }

    suspend fun downloadGame(
        targetDir: File,
        onProgress: (current: Long, total: Long) -> Unit,
        onStatus: (status: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempZipFile = File(targetDir, TEMP_ARCHIVE_NAME)
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // Check if a complete archive already exists locally
            val localArchive = findExistingArchive(targetDir)
            val archiveToExtract = if (localArchive != null) {
                onStatus("Found local game archive: ${localArchive.name}")
                localArchive
            } else {
                onStatus("Connecting to itch.io...")
                onProgress(0, 0)
                coroutineContext.ensureActive()

                val directUrl = fetchDirectDownloadUrl()
                coroutineContext.ensureActive()

                onStatus("Starting download...")
                downloadArchive(directUrl, tempZipFile, onProgress, onStatus)
                coroutineContext.ensureActive()
                tempZipFile
            }

            onStatus("Extracting game files...")
            extractArchive(archiveToExtract, targetDir, onProgress, onStatus)
            coroutineContext.ensureActive()

            // Ensure diffusion/bin directory exists, but completely remove any files (.so, .dll, etc.) inside it
            val binDir = File(targetDir, "$GAMEDIR/bin")
            binDir.mkdirs()
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete file in bin: ${file.path}", e)
                    }
                }
            }

            // Purge foreign binaries from mod directories
            purgeForeignBinaries(File(targetDir, GAMEDIR))
            purgeForeignBinaries(File(targetDir, "diffusion_russian"))
            purgeForeignBinaries(File(targetDir, "diffusion_turkish"))

            // Ensure .nomedia is placed in the base directory
            Nomedia.ensureNomedia(targetDir)

            // Remove the temporary archive once extraction is successful if downloaded by us
            if (archiveToExtract == tempZipFile && tempZipFile.exists()) {
                tempZipFile.delete()
            }

            if (!hasGameData(targetDir)) {
                return@withContext Result.failure(
                    IOException("Game files extracted, but game directory '$GAMEDIR' was not found.")
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    private fun findExistingArchive(targetDir: File): File? {
        val candidates = mutableListOf(
            File(targetDir, TEMP_ARCHIVE_NAME),
            File(targetDir, "diffusion-win-64, linux.zip"),
            File(targetDir, "diffusion-win-64.zip")
        )

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists()) {
                candidates.add(File(downloadDir, "diffusion-win-64, linux.zip"))
                candidates.add(File(downloadDir, "diffusion-win-64.zip"))
            }
        } catch (_: Exception) { }

        return candidates.firstOrNull { it.exists() && it.isFile && it.length() >= ARCHIVE_MIN_SIZE }
    }

    private fun fetchDirectDownloadUrl(): String {
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val originalCookieHandler = CookieHandler.getDefault()
        CookieHandler.setDefault(cookieManager)

        try {
            // Step 1: Request itch.io game page to obtain CSRF token and upload id
            val pageConn = (URI(ITCH_GAME_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val pageStatus = pageConn.responseCode
            if (pageStatus !in 200..299) {
                throw IOException("Failed to load itch.io page: HTTP $pageStatus")
            }

            val html = pageConn.inputStream.bufferedReader().use { it.readText() }

            val csrfMatcher = Pattern.compile("name=\"csrf_token\"\\s*value=\"([^\"]+)\"").matcher(html)
            if (!csrfMatcher.find()) {
                throw IOException("Could not parse itch.io CSRF token.")
            }
            val csrfToken = csrfMatcher.group(1)

            val uploadMatcher = Pattern.compile("data-upload_id=\"(\\d+)\"").matcher(html)
            val uploadId = if (uploadMatcher.find()) {
                uploadMatcher.group(1)
            } else {
                FALLBACK_UPLOAD_ID
            }

            // Step 2: Request direct download link via POST to itch.io file endpoint
            val fileEndpoint = "https://aynekko.itch.io/diffusion/file/$uploadId?source=view_game&as_props=1"
            val fileConn = (URI(fileEndpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", ITCH_GAME_URL)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postBytes = ("csrf_token=" + URLEncoder.encode(csrfToken, "UTF-8")).toByteArray(Charsets.UTF_8)
            fileConn.outputStream.use { it.write(postBytes) }

            val fileStatus = fileConn.responseCode
            if (fileStatus !in 200..299) {
                throw IOException("Failed to request download URL: HTTP $fileStatus")
            }

            val responseBody = fileConn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            if (!json.has("url")) {
                val errorMsg = if (json.has("errors")) json.getJSONArray("errors").join(", ") else responseBody
                throw IOException("itch.io download URL generation failed: $errorMsg")
            }

            return json.getString("url").replace("\\/", "/")
        } finally {
            CookieHandler.setDefault(originalCookieHandler)
        }
    }

    private suspend fun downloadArchive(
        directUrl: String,
        tempFile: File,
        onProgress: (current: Long, total: Long) -> Unit,
        onStatus: (status: String) -> Unit
    ) {
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        val conn = (URI(directUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (downloadedBytes > 0) {
                setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
        }

        val code = conn.responseCode
        val append: Boolean
        val totalBytes: Long

        when (code) {
            HttpURLConnection.HTTP_PARTIAL -> {
                append = true
                val remaining = conn.contentLengthLong
                totalBytes = if (remaining > 0) downloadedBytes + remaining else -1L
            }
            HttpURLConnection.HTTP_OK -> {
                append = false
                downloadedBytes = 0L
                totalBytes = conn.contentLengthLong
            }
            else -> {
                throw IOException("Unexpected HTTP response code during download: $code")
            }
        }

        var lastUpdateTime = System.currentTimeMillis()
        var lastBytesDownloaded = downloadedBytes

        conn.inputStream.use { rawInput ->
            BufferedInputStream(rawInput, BUFFER_SIZE).use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateTime
                        if (elapsed >= 300) {
                            val bytesDiff = downloadedBytes - lastBytesDownloaded
                            val speedBytesPerSec = if (elapsed > 0) (bytesDiff * 1000L) / elapsed else 0L
                            val speedStr = formatSpeed(speedBytesPerSec)

                            onProgress(downloadedBytes, totalBytes)
                            if (totalBytes > 0) {
                                val currentMB = downloadedBytes / (1024.0 * 1024.0)
                                val totalMB = totalBytes / (1024.0 * 1024.0)
                                val pct = (downloadedBytes * 100L) / totalBytes
                                onStatus(
                                    String.format(
                                        Locale.US,
                                        "Downloading: %.1f / %.1f MB (%d%%, %s)",
                                        currentMB, totalMB, pct, speedStr
                                    )
                                )
                            } else {
                                val currentMB = downloadedBytes / (1024.0 * 1024.0)
                                onStatus(
                                    String.format(
                                        Locale.US,
                                        "Downloading: %.1f MB (%s)",
                                        currentMB, speedStr
                                    )
                                )
                            }

                            lastUpdateTime = now
                            lastBytesDownloaded = downloadedBytes
                        }
                    }
                }
            }
        }

        onProgress(downloadedBytes, if (totalBytes > 0) totalBytes else downloadedBytes)
    }

    private suspend fun extractArchive(
        zipArchive: File,
        targetDir: File,
        onProgress: (current: Long, total: Long) -> Unit,
        onStatus: (status: String) -> Unit
    ) {
        val canonicalTarget = targetDir.canonicalPath
        ZipFile(zipArchive).use { zip ->
            val totalEntries = zip.size()
            var currentEntry = 0L
            val entries = zip.entries()

            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                val entry = entries.nextElement()
                currentEntry++

                if (entry.isDirectory) continue

                val rawName = entry.name
                if (!rawName.startsWith("Diffusion/")) {
                    continue
                }

                val relPath = rawName.removePrefix("Diffusion/").trim('/')
                if (relPath.isEmpty()) continue

                val topSegment = relPath.substringBefore('/').lowercase(Locale.ROOT)
                val isAllowedModDir = topSegment == GAMEDIR ||
                                      topSegment == "diffusion_russian" ||
                                      topSegment == "diffusion_turkish"
                val isIconFile = relPath.startsWith("diffusion_icon", ignoreCase = true)

                // Only extract the mod folders (diffusion, diffusion_russian, diffusion_turkish) and icon files
                if (!isAllowedModDir && !isIconFile) {
                    continue
                }

                val lower = relPath.lowercase(Locale.ROOT)

                // Skip files inside diffusion/bin:
                // We keep the bin folder itself, but completely omit foreign binaries (.so, .dll, etc.)
                if (lower.startsWith("$GAMEDIR/bin/") || lower == "$GAMEDIR/bin") {
                    continue
                }

                // Skip any foreign desktop binaries (.dll, .so, .exe, .bat, .appimage, .sh)
                if (lower.endsWith(".exe") || lower.endsWith(".bat") ||
                    lower.endsWith(".appimage") || lower.endsWith(".sh") ||
                    lower.endsWith(".dll") || lower.endsWith(".so")) {
                    continue
                }

                val destFile = File(targetDir, relPath)
                val canonicalDest = destFile.canonicalPath
                if (!canonicalDest.startsWith(canonicalTarget + File.separator)) {
                    Log.w(TAG, "Skipping entry outside target directory: $rawName")
                    continue
                }

                destFile.parentFile?.mkdirs()

                zip.getInputStream(entry).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream, bufferSize = BUFFER_SIZE)
                    }
                }

                if (currentEntry % 10L == 0L || currentEntry == totalEntries.toLong()) {
                    onProgress(currentEntry, totalEntries.toLong())
                    onStatus("Extracting: ${destFile.name} ($currentEntry / $totalEntries)")
                }
            }
        }
    }

    private fun purgeForeignBinaries(dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val name = file.name.lowercase(Locale.ROOT)
                if (name.endsWith(".dll") || name.endsWith(".so") ||
                    name.endsWith(".exe") || name.endsWith(".bat") ||
                    name.endsWith(".appimage") || name.endsWith(".sh")) {
                    try {
                        file.delete()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Could not delete foreign binary: ${file.path}", e)
                    }
                }
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    companion object {
        private const val TAG = "GameDataDownloader"
        const val PREFS_NAME = "diffusion_downloader"
        const val PREF_FIRST_RUN_CHECKED = "first_run_checked"
        const val GAMEDIR = "diffusion"
        const val DISPLAY_NAME = "Diffusion"
        const val ITCH_GAME_URL = "https://aynekko.itch.io/diffusion"
        const val FALLBACK_UPLOAD_ID = "18202749"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val TEMP_ARCHIVE_NAME = ".diffusion_download.zip"
        const val ARCHIVE_MIN_SIZE = 2_500_000_000L
        const val TIMEOUT_MS = 20000
        const val BUFFER_SIZE = 65536
    }
}
