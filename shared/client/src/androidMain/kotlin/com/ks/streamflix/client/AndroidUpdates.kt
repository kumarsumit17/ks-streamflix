package com.ks.streamflix.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ks.streamflix.client.plugin.OpenedOn
import com.ks.utility.appupdate.UpdatePlatform
import java.io.File

/**
 * Android's half of an update: the APK goes to `cacheDir/updates/`, must have this app's package
 * name and signing certificate, and is handed to the system installer, which asks the user.
 */
class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {
    override fun downloadDir() = File(context.cacheDir, "updates")

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun verify(file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "not an app"
        if (archive.packageName != context.packageName) return "another app (${archive.packageName})"
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val mine = certs(installed)
        val theirs = certs(archive)
        return if (mine.isNotEmpty() && mine == theirs) null else "signed by someone else"
    }

    override fun open(file: File): OpenedOn {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return OpenedOn.Android
    }

    override fun openPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

fun androidUpdater(context: Context, settings: com.ks.streamflix.client.settings.SettingsStore, version: String, platform: UpdatePlatform = UpdatePlatform.ANDROID) =
    GitHubUpdater(GitHubUpdater.REPO, version, platform, settings, AndroidUpdateInstaller(context)).also { it.start() }
