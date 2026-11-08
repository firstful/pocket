package com.sutliff.pocket.permissions

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Notification-listener opt-in helper (todo #424).
 *
 * Notification listening is DISABLED BY DEFAULT and opt-in only:
 * the user must both (1) flip the Settings > Permissions toggle (persisted in
 * DataStore as [com.sutliff.pocket.data.PermissionOptIns.notificationListenerEnabled])
 * AND (2) grant the system Notification Listener access (NotificationManagerCompat
 * enabled-listener set). The trigger service additionally gates on the DataStore
 * flag, so a stale system grant alone does not re-enable Pocket.
 *
 * Blast radius (shown in Settings): granting Notification Listener gives Pocket
 * read access to ALL app notifications (titles/bodies) — much broader than any
 * single app's notifications. The service only forwards messages matching its
 * keyword rule to the agent, but the OS still delivers every notification to
 * the service process.
 */
object NotificationOptIns {

    const val DISABLED_MESSAGE =
        "Notification triggers are disabled. Enable them in Settings > Permissions " +
            "(grants Notification Listener access — reads all app notifications)."

    /** True when the system Notification Listener grant is present. */
    fun isListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun stateLabel(context: Context): String =
        if (isListenerEnabled(context)) "Enabled" else "Disabled"

    fun systemPromptNote(context: Context): String =
        if (isListenerEnabled(context)) {
            "Notification-listener triggers are enabled. Pocket may act on notifications matching its rules."
        } else {
            "Notification triggers are disabled by default. If the user asks to act on notifications, " +
                "instruct them to enable Notification access in Settings > Permissions."
        }
}
