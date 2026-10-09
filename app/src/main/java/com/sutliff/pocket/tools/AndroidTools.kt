package com.sutliff.pocket.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

// ── Android Service Tools ────────────────────────────────────────────────────
// Each tool wraps an Android API and exposes it to the agent runtime.
// Tools that need a Context receive it via the ToolContext.

data class ToolContext(
    val context: Context,
    val appDir: String
)

class LocationTool : Tool {
    override val name = "location"
    override val description = "Get current device location (GPS or network). Returns latitude, longitude, accuracy."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("provider") {
                put("type", "string")
                put("description", "gps or network (default: gps)")
            }
        }
    }

    override fun execute(args: Map<String, String>): String {
        return "Location tool requires ToolContext — use LocationToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val lm = ctx.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = args["provider"] ?: "gps"
            val loc = lm.getLastKnownLocation(provider)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return "No location available"
            buildString {
                appendLine("Latitude: ${loc.latitude}")
                appendLine("Longitude: ${loc.longitude}")
                appendLine("Accuracy: ${loc.accuracy}m")
                appendLine("Provider: ${loc.provider}")
                appendLine("Time: ${loc.time}")
            }
        } catch (e: SecurityException) {
            "Permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class BatteryTool : Tool {
    override val name = "battery"
    override val description = "Get battery status: level, charging state, temperature, voltage."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override fun execute(args: Map<String, String>): String {
        return "Battery tool requires ToolContext — use BatteryToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val bm = ctx.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val plugged = status == BatteryManager.BATTERY_STATUS_CHARGING
            val statusStr = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                else -> "unknown"
            }
            val pluggedStr = if (plugged) "AC" else "unplugged"
            buildString {
                appendLine("Level: $level%")
                appendLine("Status: $statusStr")
                appendLine("Plugged: $pluggedStr")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class WifiTool : Tool {
    override val name = "wifi"
    override val description = "Get Wi-Fi connection info or scan for nearby networks."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "info or scan (default: info)")
            }
        }
    }

    override fun execute(args: Map<String, String>): String {
        return "WiFi tool requires ToolContext — use WifiToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val action = args["action"] ?: "info"
            val wm = ctx.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            when (action) {
                "info" -> {
                    val info = wm.connectionInfo
                    buildString {
                        appendLine("SSID: ${info.ssid}")
                        appendLine("BSSID: ${info.bssid}")
                        appendLine("RSSI: ${info.rssi} dBm")
                        appendLine("Link speed: ${info.linkSpeed} Mbps")
                        appendLine("Frequency: ${info.frequency} MHz")
                    }
                }
                "scan" -> {
                    val results = wm.scanResults
                    if (results.isEmpty()) return "No networks found"
                    results.joinToString("\n") { r ->
                        "${r.SSID} (${r.level} dBm, ${r.frequency} MHz)"
                    }
                }
                else -> "Unknown action: $action"
            }
        } catch (e: SecurityException) {
            "Permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class ClipboardTool : Tool {
    override val name = "clipboard"
    override val description = "Get or set clipboard content."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "get or set (default: get)")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to set (required for set)")
            }
        }
    }

    override fun execute(args: Map<String, String>): String {
        return "Clipboard tool requires ToolContext — use ClipboardToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val cm = ctx.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            when (args["action"] ?: "get") {
                "get" -> {
                    val clip = cm.primaryClip
                    if (clip == null || clip.itemCount == 0) return "Clipboard empty"
                    clip.getItemAt(0).text?.toString() ?: "Clipboard empty"
                }
                "set" -> {
                    val text = args["text"] ?: return "No text provided"
                    cm.setPrimaryClip(ClipData.newPlainText("agent", text))
                    "Clipboard set: $text"
                }
                else -> "Unknown action"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class VibrateTool : Tool {
    override val name = "vibrate"
    override val description = "Vibrate the device for a specified duration."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("duration") {
                put("type", "integer")
                put("description", "Duration in milliseconds (default: 500)")
            }
        }
    }

    override fun execute(args: Map<String, String>): String {
        return "Vibrate tool requires ToolContext — use VibrateToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val duration = args["duration"]?.toLong() ?: 500L
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            "Vibrated for ${duration}ms"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class NotificationTool : Tool {
    override val name = "notify"
    override val description = "Show a notification on the device."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "Notification title")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "Notification body text")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("body")) }
    }

    override fun execute(args: Map<String, String>): String {
        return "Notification tool requires ToolContext — use NotificationToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val title = args["title"] ?: return "No title"
            val body = args["body"] ?: return "No body"
            val channelId = "agent_notifications"
            val nm = ctx.context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Agent", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(channel)
            }
            val notification = android.app.Notification.Builder(ctx.context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notification)
            "Notification shown: $title"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class SmsReplyTool : Tool {
    override val name = "sms_reply"
    override val description = "Send an SMS reply to a phone number."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("to") {
                put("type", "string")
                put("description", "Phone number to send to")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "SMS body text")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("to")); add(JsonPrimitive("message")) }
    }

    override fun execute(args: Map<String, String>): String {
        return "SMS tool requires ToolContext — use SmsReplyToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val to = args["to"] ?: return "No phone number"
            val message = args["message"] ?: return "No message"
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(to, null, message, null, null)
            "SMS sent to $to"
        } catch (e: SecurityException) {
            "Permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class ContactsTool : Tool {
    override val name = "contacts"
    override val description = "Search contacts by name."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Name to search for")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    override fun execute(args: Map<String, String>): String {
        return "Contacts tool requires ToolContext — use ContactsToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val query = args["query"] ?: return "No query"
            val results = mutableListOf<String>()
            val cursor = ctx.context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    results.add("$name: $number")
                }
            }
            if (results.isEmpty()) return "No contacts found for '$query'"
            results.joinToString("\n")
        } catch (e: SecurityException) {
            "Permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class DeviceInfoTool : Tool {
    override val name = "device_info"
    override val description = "Get device information: model, brand, Android version, API level."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override fun execute(args: Map<String, String>): String {
        return "DeviceInfo tool requires ToolContext — use DeviceInfoToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return buildString {
            appendLine("Model: ${Build.MODEL}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("API: ${Build.VERSION.SDK_INT}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
        }
    }
}

class NetworkTool : Tool {
    override val name = "network"
    override val description = "Get network connectivity status and type."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override fun execute(args: Map<String, String>): String {
        return "Network tool requires ToolContext — use NetworkToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val cm = ctx.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "No active network"
            val caps = cm.getNetworkCapabilities(network) ?: return "No capabilities"
            buildString {
                appendLine("Connected: true")
                appendLine("WiFi: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}")
                appendLine("Cellular: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
                appendLine("Ethernet: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}")
                appendLine("VPN: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
                appendLine("Internet: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
                appendLine("Validated: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class LaunchAppTool : Tool {
    override val name = "launch_app"
    override val description = "Launch an app by package name."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package") {
                put("type", "string")
                put("description", "Package name (e.g., com.android.chrome)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("package")) }
    }

    override fun execute(args: Map<String, String>): String {
        return "LaunchApp tool requires ToolContext — use LaunchAppToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val pkg = args["package"] ?: return "No package name"
            val intent = ctx.context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "App not found: $pkg"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.context.startActivity(intent)
            "Launched: $pkg"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class TorchTool : Tool {
    override val name = "torch"
    override val description = "Turn the flashlight on or off."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("state") {
                put("type", "string")
                put("description", "on or off")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("state")) }
    }

    override fun execute(args: Map<String, String>): String {
        return "Torch tool requires ToolContext — use TorchToolCtx"
    }

    fun execute(args: Map<String, String>, ctx: ToolContext): String {
        return try {
            val state = args["state"] ?: return "No state"
            val cm = ctx.context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flash available"
            cm.setTorchMode(cameraId, state == "on")
            "Torch $state"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

// ── Registry with all tools ──────────────────────────────────────────────────

fun AndroidToolRegistry(ctx: ToolContext): ToolRegistry {
    val registry = ToolRegistry()

    // Core tools (no context needed)
    registry.register(object : Tool {
        override val name = "exec"
        override val description = "Execute a shell command in the Alpine Linux proot environment."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to execute")
                }
                putJsonObject("cwd") {
                    put("type", "string")
                    put("description", "Working directory")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        }
        override fun execute(args: Map<String, String>): String {
            val command = args["command"] ?: return "Error: no command"
            return try {
                val process = ProcessBuilder(*command.split(" ").toTypedArray())
                    .directory(java.io.File(args["cwd"] ?: "/root"))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                "Exit code: $exitCode\n$output"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    })

    registry.register(object : Tool {
        override val name = "fs_read"
        override val description = "Read a file from the filesystem."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute file path")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "Byte offset")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max bytes (default 8192)")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        }
        override fun execute(args: Map<String, String>): String {
            val path = args["path"] ?: return "Error: no path"
            val offset = args["offset"]?.toInt() ?: 0
            val limit = args["limit"]?.toInt() ?: 8192
            return try {
                val file = java.io.File(path)
                if (!file.exists()) return "File not found: $path"
                val raf = java.io.RandomAccessFile(file, "r")
                raf.seek(offset.toLong())
                val buffer = ByteArray(limit)
                val read = raf.read(buffer)
                raf.close()
                String(buffer, 0, read)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    })

    registry.register(object : Tool {
        override val name = "fs_list"
        override val description = "List directory contents."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Directory path")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        }
        override fun execute(args: Map<String, String>): String {
            val path = args["path"] ?: return "Error: no path"
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.isDirectory) return "Not a directory: $path"
            return dir.listFiles()?.joinToString("\n") { f ->
                val type = if (f.isDirectory) "DIR" else "FILE"
                val size = if (f.isFile) " (${f.length()} bytes)" else ""
                "$type  ${f.name}$size"
            } ?: "(empty)"
        }
    })

    registry.register(object : Tool {
        override val name = "fs_write"
        override val description = "Write content to a file."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Content to write")
                }
                putJsonObject("append") {
                    put("type", "boolean")
                    put("description", "Append instead of overwrite")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("content")) }
        }
        override fun execute(args: Map<String, String>): String {
            val path = args["path"] ?: return "Error: no path"
            val content = args["content"] ?: ""
            val append = args["append"]?.toBoolean() ?: false
            return try {
                val file = java.io.File(path)
                file.parentFile?.mkdirs()
                java.io.FileWriter(file, append).use { it.write(content) }
                "Wrote ${content.length} chars to $path"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    })

    registry.register(object : Tool {
        override val name = "fs_exists"
        override val description = "Check if a file or directory exists."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Path to check")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        }
        override fun execute(args: Map<String, String>): String {
            val path = args["path"] ?: return "Error: no path"
            val file = java.io.File(path)
            if (!file.exists()) return "Not found: $path"
            val type = if (file.isDirectory) "directory" else "file"
            return "$path exists ($type, ${file.length()} bytes)"
        }
    })

    // Android service tools (need context)
    val locationTool = LocationTool()
    registry.register(object : Tool {
        override val name = locationTool.name
        override val description = locationTool.description
        override val parameters = locationTool.parameters
        override fun execute(args: Map<String, String>): String = locationTool.execute(args, ctx)
    })

    val batteryTool = BatteryTool()
    registry.register(object : Tool {
        override val name = batteryTool.name
        override val description = batteryTool.description
        override val parameters = batteryTool.parameters
        override fun execute(args: Map<String, String>): String = batteryTool.execute(args, ctx)
    })

    val wifiTool = WifiTool()
    registry.register(object : Tool {
        override val name = wifiTool.name
        override val description = wifiTool.description
        override val parameters = wifiTool.parameters
        override fun execute(args: Map<String, String>): String = wifiTool.execute(args, ctx)
    })

    val clipboardTool = ClipboardTool()
    registry.register(object : Tool {
        override val name = clipboardTool.name
        override val description = clipboardTool.description
        override val parameters = clipboardTool.parameters
        override fun execute(args: Map<String, String>): String = clipboardTool.execute(args, ctx)
    })

    val vibrateTool = VibrateTool()
    registry.register(object : Tool {
        override val name = vibrateTool.name
        override val description = vibrateTool.description
        override val parameters = vibrateTool.parameters
        override fun execute(args: Map<String, String>): String = vibrateTool.execute(args, ctx)
    })

    val notificationTool = NotificationTool()
    registry.register(object : Tool {
        override val name = notificationTool.name
        override val description = notificationTool.description
        override val parameters = notificationTool.parameters
        override fun execute(args: Map<String, String>): String = notificationTool.execute(args, ctx)
    })

    val smsReplyTool = SmsReplyTool()
    registry.register(object : Tool {
        override val name = smsReplyTool.name
        override val description = smsReplyTool.description
        override val parameters = smsReplyTool.parameters
        override fun execute(args: Map<String, String>): String = smsReplyTool.execute(args, ctx)
    })

    val contactsTool = ContactsTool()
    registry.register(object : Tool {
        override val name = contactsTool.name
        override val description = contactsTool.description
        override val parameters = contactsTool.parameters
        override fun execute(args: Map<String, String>): String = contactsTool.execute(args, ctx)
    })

    val deviceInfoTool = DeviceInfoTool()
    registry.register(object : Tool {
        override val name = deviceInfoTool.name
        override val description = deviceInfoTool.description
        override val parameters = deviceInfoTool.parameters
        override fun execute(args: Map<String, String>): String = deviceInfoTool.execute(args, ctx)
    })

    val networkTool = NetworkTool()
    registry.register(object : Tool {
        override val name = networkTool.name
        override val description = networkTool.description
        override val parameters = networkTool.parameters
        override fun execute(args: Map<String, String>): String = networkTool.execute(args, ctx)
    })

    val launchAppTool = LaunchAppTool()
    registry.register(object : Tool {
        override val name = launchAppTool.name
        override val description = launchAppTool.description
        override val parameters = launchAppTool.parameters
        override fun execute(args: Map<String, String>): String = launchAppTool.execute(args, ctx)
    })

    val torchTool = TorchTool()
    registry.register(object : Tool {
        override val name = torchTool.name
        override val description = torchTool.description
        override val parameters = torchTool.parameters
        override fun execute(args: Map<String, String>): String = torchTool.execute(args, ctx)
    })

    // Health check
    registry.register(object : Tool {
        override val name = "health"
        override val description = "Check environment health."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override fun execute(args: Map<String, String>): String {
            return buildString {
                appendLine("Proot dir: ${java.io.File(ctx.appDir, "proot").exists()}")
                appendLine("Alpine dir: ${java.io.File(ctx.appDir, "alpine").exists()}")
                appendLine("Android: ${Build.VERSION.RELEASE}")
                appendLine("Device: ${Build.MODEL}")
            }
        }
    })

    return registry
}