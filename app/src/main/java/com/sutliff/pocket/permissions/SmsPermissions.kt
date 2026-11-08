package com.sutliff.pocket.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * SMS opt-in helper.
 *
 * SMS is DISABLED BY DEFAULT: permissions are declared in the manifest but only
 * so `checkSelfPermission` will always return denied unless the user explicitly
 * grants them via Settings > SMS permissions (runtime request). Modern Android
 * restricts these permissions due to OTP exploitation, so the app must not
 * request them at startup or silently.
 *
 * Declared != granted: the permissions ARE declared in the manifest so the
 * runtime request works, but they are never requested at startup and tools
 * gate on checkSelfPermission, so SMS stays disabled until the user opts in.
 */
object SmsPermissions {

    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    const val DISABLED_MESSAGE =
        "SMS is disabled. Ask the user to enable it in Settings > SMS permissions."

    /** True only when every SMS permission required by the sms_reply tool is granted. */
    fun isGranted(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    /** Human-readable state for Settings UI. */
    fun stateLabel(context: Context): String =
        if (isGranted(context)) "Enabled" else "Disabled"

    /**
     * Note appended to the agent system prompt when SMS is not granted, so the
     * model explains the opt-in path instead of silently failing.
     */
    fun systemPromptNote(context: Context): String =
        if (isGranted(context)) {
            "SMS tools are enabled for this device."
        } else {
            "SMS tools are disabled by default. If the user asks for SMS tasks, " +
                "instruct them to enable it in Settings > SMS permissions."
        }
}
