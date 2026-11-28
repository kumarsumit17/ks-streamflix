package com.ks.streamflix.client

import com.ks.streamflix.client.plugin.AppUpdater
import com.ks.streamflix.client.plugin.OpenedOn
import com.ks.streamflix.client.plugin.UpdateFailure
import com.ks.streamflix.client.plugin.UpdateStatus
import com.ks.streamflix.client.settings.SettingsStore
import com.ks.utility.appupdate.AppUpdate
import com.ks.utility.appupdate.GitHubRepo
import com.ks.utility.appupdate.ReleaseAsset
import com.ks.utility.appupdate.Sha256
import com.ks.utility.appupdate.UpdateOutcome
import com.ks.utility.appupdate.UpdatePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** The platform's part of an update: where downloads go, checks of the file, and the installer. */
interface UpdateInstaller {
    /** A folder of the app's own, emptied before each download. */
    fun downloadDir(): File

    /** True when the system lets this app install the file (Android's "install unknown apps"). */
    fun canInstall(): Boolean = true

    /** Extra checks of the downloaded file (Android: same package and signer); a reason when it fails. */
    fun verify(file: File): String? = null

    /** Hands the verified file to the system. */
    fun open(file: File): OpenedOn

    fun openPermissionSettings() {}
}

/**
 * The app's updates from GitHub, by ks-utility's rules (its `specs/app-update.md`): one GET of the
 * latest release with no account, at most once a day by itself (10 s after start, then hourly if
 * due), never by itself for a build from source; the file is used only when its SHA-256 matches
 * GitHub's and the platform's checks pass.
 */
class GitHubUpdater(
    repo: String,
    override val currentVersion: String,
    private val platform: UpdatePlatform,
    private val settings: SettingsStore,
    private val installer: UpdateInstaller,
    private val fetch: (GitHubRepo) -> String = ::fetchReleases,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AppUpdater {
    private val repo = requireNotNull(GitHubRepo.parse(repo)) { "Not owner/name: $repo" }
    override val shownVersion = currentVersion.takeUnless { AppUpdate.isDevVersion(it) }
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    override val status: StateFlow<UpdateStatus> = _status
    private val _auto = MutableStateFlow(settings.getString(AUTO) != "false")
    override val autoCheck: StateFlow<Boolean> = _auto
    private var found: Pair<String, ReleaseAsset>? = null
    private var job: Job? = null

    fun start() {
        scope.launch {
            delay(AppUpdate.STARTUP_DELAY_MS)
            while (isActive) {
                checkIfDue()
                delay(AppUpdate.RECHECK_EVERY_MS)
            }
        }
    }

    /** Android and TV: also when the app comes back to the foreground. */
    fun onForeground() {
        scope.launch { checkIfDue() }
    }

    private fun checkIfDue() {
        val last = settings.getString(LAST_CHECK)?.toLongOrNull()
        if (job?.isActive != true && AppUpdate.isCheckDue(_auto.value, currentVersion, last, System.currentTimeMillis())) check(silent = true)
    }

    override fun setAutoCheck(enabled: Boolean) {
        settings.putString(AUTO, enabled.toString())
        _auto.value = enabled
    }

    override fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch { check(silent = false) }
    }

    /** One check; [silent] (the automatic one) shows only a newer version, never errors. */
    fun check(silent: Boolean) {
        if (!silent) _status.value = UpdateStatus.Checking
        val body = try {
            fetch(repo)
        } catch (e: IOException) {
            if (!silent) _status.value = UpdateStatus.Failed(UpdateFailure.Check, e.message ?: e.javaClass.simpleName)
            return
        }
        settings.putString(LAST_CHECK, System.currentTimeMillis().toString())
        when (val outcome = AppUpdate.evaluateReleases(body, repo, currentVersion, platform)) {
            null -> if (!silent) _status.value = UpdateStatus.Failed(UpdateFailure.Unreadable)
            is UpdateOutcome.UpToDate -> if (!silent) _status.value = UpdateStatus.UpToDate(outcome.version)
            is UpdateOutcome.NoInstaller -> if (!silent) _status.value = UpdateStatus.NoInstaller(outcome.version)
            is UpdateOutcome.Available -> {
                found = outcome.version to outcome.asset
                _status.value = UpdateStatus.Available(outcome.version, outcome.asset.size)
            }
        }
    }

    override fun install() {
        val (version, asset) = found ?: return
        if (job?.isActive == true) return
        if (!installer.canInstall()) {
            _status.value = UpdateStatus.NeedsPermission
            return
        }
        job = scope.launch {
            _status.value = UpdateStatus.Downloading(version)
            val dir = installer.downloadDir().apply { deleteRecursively(); mkdirs() }
            val file = File(dir, asset.name.substringAfterLast('/'))
            try {
                download(asset.url, file, asset.size)
            } catch (e: IOException) {
                file.delete()
                _status.value = UpdateStatus.Failed(UpdateFailure.Download, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            if (file.length() != asset.size || !Sha256.matches(Sha256.hex(file), asset.sha256)) {
                file.delete()
                _status.value = UpdateStatus.Failed(UpdateFailure.Damaged)
                return@launch
            }
            installer.verify(file)?.let { reason ->
                file.delete()
                _status.value = UpdateStatus.Failed(UpdateFailure.Install, reason)
                return@launch
            }
            _status.value = try {
                UpdateStatus.Opened(installer.open(file))
            } catch (e: Exception) {
                UpdateStatus.Failed(UpdateFailure.Open, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun openInstallPermissionSettings() = installer.openPermissionSettings()

    companion object {
        const val REPO = "kumasumit/ks-streamflix"
        private const val AUTO = "update.auto"
        private const val LAST_CHECK = "update.lastCheck"
        private const val MAX_RESPONSE = 1 shl 20

        /**
         * The release list's JSON, with ks-utility's headers only. A platform release
         * (`android-v…`) is not marked latest, so the list is read, not `releases/latest`.
         */
        fun fetchReleases(repo: GitHubRepo): String {
            val c = URI(repo.releasesApiUrl).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                repo.requestHeaders.forEach(c::setRequestProperty)
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readNBytes(MAX_RESPONSE).decodeToString() }
            } finally {
                c.disconnect()
            }
        }

        /** Downloads over HTTPS, following GitHub's redirects to its file host (HTTPS only), up to [maxBytes]. */
        fun download(url: String, to: File, maxBytes: Long) {
            var current = URI(url)
            repeat(5) {
                if (current.scheme != "https") throw IOException("Refused ${current.scheme}")
                val c = current.toURL().openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("User-Agent", "ks-streamflix")
                    when (val code = c.responseCode) {
                        200 -> {
                            c.inputStream.use { input -> to.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    if (total > maxBytes) throw IOException("larger than announced")
                                    out.write(buffer, 0, n)
                                }
                            } }
                            return
                        }
                        301, 302, 303, 307, 308 -> current = current.resolve(c.getHeaderField("Location") ?: throw IOException("HTTP $code"))
                        else -> throw IOException("HTTP $code")
                    }
                } finally {
                    c.disconnect()
                }
            }
            throw IOException("Too many redirects")
        }
    }
}
