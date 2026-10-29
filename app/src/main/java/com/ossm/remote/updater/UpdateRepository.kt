package com.ossm.remote.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult
    data class InvalidManifest(val message: String) : UpdateCheckResult
    data object ServerUnavailable : UpdateCheckResult
}

sealed interface PcManifestResult {
    data class Found(val manifest: UpdateManifest) : PcManifestResult
    data object Invalid : PcManifestResult
    data object Unreachable : PcManifestResult
}

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    val manualUrl: Flow<String> = dataStore.data.map { prefs ->
        normalizeManifestUrl(prefs[MANUAL_URL].orEmpty()).orEmpty()
    }

    suspend fun saveManualUrl(url: String) {
        dataStore.edit { prefs -> prefs[MANUAL_URL] = normalizeManifestUrl(url).orEmpty() }
    }

    val pcServerUrl: Flow<String> = dataStore.data.map { prefs ->
        normalizePcServerUrl(prefs[PC_SERVER_URL].orEmpty()).orEmpty()
    }

    val pcListening: Flow<Boolean> = dataStore.data.map { prefs -> prefs[PC_LISTENING] ?: false }

    suspend fun savePcServerUrl(url: String) {
        dataStore.edit { prefs -> prefs[PC_SERVER_URL] = normalizePcServerUrl(url).orEmpty() }
    }

    suspend fun savePcListening(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PC_LISTENING] = enabled }
    }

    /** Short timeout, no state change: the PC server is usually off, so failure is the normal case. */
    suspend fun fetchPcManifest(manifestUrl: String): PcManifestResult = withContext(Dispatchers.IO) {
        val json = fetchText(manifestUrl, PC_TIMEOUT_MILLIS) ?: return@withContext PcManifestResult.Unreachable
        UpdateManifestParser.parse(json, manifestUrl)?.let(PcManifestResult::Found) ?: PcManifestResult.Invalid
    }

    suspend fun shouldCheckOnStartup(nowMillis: Long = System.currentTimeMillis()): Boolean =
        dataStore.data.map { prefs ->
            nowMillis - (prefs[LAST_CHECK_MILLIS] ?: 0L) >= CHECK_INTERVAL_MILLIS
        }.first()

    suspend fun checkForUpdate(installedVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        val manifestUrl = manualUrl.first().takeIf(String::isNotBlank)
            ?: return@withContext UpdateCheckResult.ServerUnavailable
        dataStore.edit { prefs -> prefs[LAST_CHECK_MILLIS] = System.currentTimeMillis() }
        val json = fetchText(manifestUrl) ?: return@withContext UpdateCheckResult.ServerUnavailable
        val manifest = UpdateManifestParser.parse(json, manifestUrl)
            ?: return@withContext UpdateCheckResult.InvalidManifest("Invalid update manifest")
        if (UpdateManifestParser.isNewer(manifest, installedVersionCode)) {
            UpdateCheckResult.Available(manifest)
        } else {
            UpdateCheckResult.NoUpdate
        }
    }

    suspend fun downloadVerifiedApk(manifest: UpdateManifest): File? = withContext(Dispatchers.IO) {
        val destination = File(context.cacheDir, "ossm-update.apk")
        val staging = File(context.cacheDir, "ossm-update.apk.part")
        staging.delete()
        val connection = openConnection(manifest.apkUrl, DOWNLOAD_TIMEOUT_MILLIS) ?: return@withContext null
        try {
            connection.inputStream.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (sha256(staging) != manifest.sha256) return@withContext null
            if (destination.exists()) destination.delete()
            if (!staging.renameTo(destination)) return@withContext null
            destination
        } catch (_: Exception) {
            null
        } finally {
            staging.delete()
            connection.disconnect()
        }
    }

    private fun fetchText(url: String, timeoutMillis: Int = NETWORK_TIMEOUT_MILLIS): String? = openConnection(url, timeoutMillis)?.let { connection ->
        try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, timeoutMillis: Int = NETWORK_TIMEOUT_MILLIS): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = false
            requestMethod = "GET"
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 5_000
        // Connect + read: a PC server that does not answer within ~2 s is off, give up silently.
        const val PC_TIMEOUT_MILLIS = 1_000
        const val DOWNLOAD_TIMEOUT_MILLIS = 30_000
        val PC_SERVER_URL = stringPreferencesKey("pc_server_url")
        val PC_LISTENING = booleanPreferencesKey("pc_listening")
        const val CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1_000L
        val MANUAL_URL = stringPreferencesKey("manual_manifest_url")
        val LAST_CHECK_MILLIS = longPreferencesKey("last_update_check_millis")
    }
}
