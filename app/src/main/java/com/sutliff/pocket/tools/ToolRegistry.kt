package com.sutliff.pocket.tools

import com.sutliff.pocket.data.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    fun execute(args: Map<String, String>): String
}


class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                function = ToolFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }
}

// ── Built-in Tools (run on Android, shell out to proot env) ──────────────────

private const val PROOT_DIR = "proot"
private const val ALPINE_DIR = "alpine"

class ExecTool : Tool {
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
        putJsonArray("required") { add("command") }
    }

    override fun execute(args: Map<String, String>): String {
        val command = args["command"] ?: return "Error: no command"
        val cwd = args["cwd"] ?: "/root"
        return try {
            val process = ProcessBuilder(*command.split(" ").toTypedArray())
                .directory(java.io.File(cwd))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "Exit code: $exitCode\n$output"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class FsReadTool : Tool {
    override val name = "fs_read"
    override val description = "Read a file from the filesystem. Supports offset/limit for large files."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute file path")
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "Byte offset to start reading from")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Max bytes to read (default 8192)")
            }
        }
        putJsonArray("required") { add("path") }
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
}

class FsListTool : Tool {
    override val name = "fs_list"
    override val description = "List directory contents on the filesystem."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Directory path to list")
            }
        }
        putJsonArray("required") { add("path") }
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
}

class FsWriteTool : Tool {
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
        putJsonArray("required") { add("path"); add("content") }
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
}

class HealthCheckTool : Tool {
    override val name = "health"
    override val description = "Check the proot Alpine environment and filesystem health."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override fun execute(args: Map<String, String>): String {
        val appDir = "/data/local/tmp/pocket"
        val prootDir = java.io.File(appDir, PROOT_DIR)
        val alpineDir = java.io.File(appDir, ALPINE_DIR)
        return buildString {
            appendLine("Proot dir: ${prootDir.absolutePath} (exists: ${prootDir.exists()})")
            appendLine("Alpine dir: ${alpineDir.absolutePath} (exists: ${alpineDir.exists()})")
            appendLine("Android version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("Device: ${android.os.Build.MODEL}")
        }
    }
}

fun DefaultToolRegistry(): ToolRegistry {
    val registry = ToolRegistry()
    registry.register(ExecTool())
    registry.register(FsReadTool())
    registry.register(FsListTool())
    registry.register(FsWriteTool())
    registry.register(HealthCheckTool())
    return registry
}
