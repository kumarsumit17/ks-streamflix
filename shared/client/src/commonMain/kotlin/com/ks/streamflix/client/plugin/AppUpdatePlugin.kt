package com.ks.streamflix.client.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.common_app_update_allow_install
import com.ks.streamflix.client.resources.common_app_update_allow_install_button
import com.ks.streamflix.client.resources.common_app_update_auto
import com.ks.streamflix.client.resources.common_app_update_auto_hint
import com.ks.streamflix.client.resources.common_app_update_available
import com.ks.streamflix.client.resources.common_app_update_check_button
import com.ks.streamflix.client.resources.common_app_update_check_failed
import com.ks.streamflix.client.resources.common_app_update_checking
import com.ks.streamflix.client.resources.common_app_update_current
import com.ks.streamflix.client.resources.common_app_update_damaged
import com.ks.streamflix.client.resources.common_app_update_dev
import com.ks.streamflix.client.resources.common_app_update_download_failed
import com.ks.streamflix.client.resources.common_app_update_downloading
import com.ks.streamflix.client.resources.common_app_update_install_button
import com.ks.streamflix.client.resources.common_app_update_install_failed
import com.ks.streamflix.client.resources.common_app_update_no_installer
import com.ks.streamflix.client.resources.common_app_update_open_failed
import com.ks.streamflix.client.resources.common_app_update_opened_android
import com.ks.streamflix.client.resources.common_app_update_opened_mac_closes
import com.ks.streamflix.client.resources.common_app_update_opened_windows
import com.ks.streamflix.client.resources.common_app_update_section
import com.ks.streamflix.client.resources.common_app_update_unreadable
import com.ks.streamflix.client.resources.common_app_update_up_to_date
import com.ks.streamflix.client.ui.ChoiceButton
import com.ks.streamflix.client.ui.focusRing
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** Where a verified update was handed over, for the sentence shown. */
enum class OpenedOn { Mac, Windows, Android }

enum class UpdateFailure { Check, Unreadable, Download, Damaged, Open, Install }

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val version: String, val size: Long) : UpdateStatus
    data class NoInstaller(val version: String) : UpdateStatus
    data object NeedsPermission : UpdateStatus
    data class Downloading(val version: String) : UpdateStatus
    data class Opened(val on: OpenedOn) : UpdateStatus
    data class Failed(val failure: UpdateFailure, val detail: String = "") : UpdateStatus
}

/** The app's own updates from its GitHub releases (ks-utility's app-update rules); one per platform. */
interface AppUpdater {
    val currentVersion: String
    val isDevBuild: Boolean
    val status: StateFlow<UpdateStatus>
    val autoCheck: StateFlow<Boolean>
    fun setAutoCheck(enabled: Boolean)
    fun checkNow()
    fun install()
    fun openInstallPermissionSettings() {}
}

/** Plugin `app-update`: the Settings section "App updates" (version, the automatic check, check, install). */
class AppUpdatePlugin(private val updater: AppUpdater) : ClientPlugin {
    override val id = "app-update"

    override fun install(context: ClientPluginContext) {
        context.addSettingsSection { Section(updater) }
    }
}

@Composable
private fun Section(u: AppUpdater) {
    val status by u.status.collectAsState()
    val auto by u.autoCheck.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.common_app_update_section), style = MaterialTheme.typography.titleMedium)
        Text(if (u.isDevBuild) stringResource(Res.string.common_app_update_dev) else stringResource(Res.string.common_app_update_current, u.currentVersion), color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(stringResource(Res.string.common_app_update_auto), auto) { u.setAutoCheck(!auto) }
        }
        Text(stringResource(Res.string.common_app_update_auto_hint), style = MaterialTheme.typography.bodySmall, color = muted)
        val line = when (val s = status) {
            UpdateStatus.Idle -> null
            UpdateStatus.Checking -> stringResource(Res.string.common_app_update_checking)
            is UpdateStatus.UpToDate -> stringResource(Res.string.common_app_update_up_to_date, s.version)
            is UpdateStatus.Available -> stringResource(Res.string.common_app_update_available, s.version)
            is UpdateStatus.NoInstaller -> stringResource(Res.string.common_app_update_no_installer, s.version)
            UpdateStatus.NeedsPermission -> stringResource(Res.string.common_app_update_allow_install)
            is UpdateStatus.Downloading -> stringResource(Res.string.common_app_update_downloading, s.version)
            is UpdateStatus.Opened -> when (s.on) {
                OpenedOn.Mac -> stringResource(Res.string.common_app_update_opened_mac_closes, "KS StreamFlix")
                OpenedOn.Windows -> stringResource(Res.string.common_app_update_opened_windows)
                OpenedOn.Android -> stringResource(Res.string.common_app_update_opened_android)
            }
            is UpdateStatus.Failed -> when (s.failure) {
                UpdateFailure.Check -> stringResource(Res.string.common_app_update_check_failed, s.detail)
                UpdateFailure.Unreadable -> stringResource(Res.string.common_app_update_unreadable)
                UpdateFailure.Download -> stringResource(Res.string.common_app_update_download_failed, s.detail)
                UpdateFailure.Damaged -> stringResource(Res.string.common_app_update_damaged)
                UpdateFailure.Open -> stringResource(Res.string.common_app_update_open_failed, s.detail)
                UpdateFailure.Install -> stringResource(Res.string.common_app_update_install_failed, s.detail)
            }
        }
        line?.let { Text(it, color = muted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(stringResource(Res.string.common_app_update_check_button), false) { u.checkNow() }
            val s = status
            if (s is UpdateStatus.Available) {
                ChoiceButton(stringResource(Res.string.common_app_update_install_button, formatMb(s.size)), true) { u.install() }
            }
            if (s == UpdateStatus.NeedsPermission) {
                ChoiceButton(stringResource(Res.string.common_app_update_allow_install_button), true) { u.openInstallPermissionSettings() }
            }
        }
    }
}

private fun formatMb(bytes: Long) = "${(bytes * 10 / 1_048_576) / 10.0} MB"
