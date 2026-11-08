package com.sutliff.pocket.tools

/**
 * Map Android-host paths that are not writable by the app (e.g. /tmp) to an
 * app-owned directory under cacheDir, so fs_read/fs_list/fs_write/fs_exists
 * stay consistent and writes succeed. Non-/tmp paths pass through unchanged.
 */
fun resolveSandboxPath(path: String, ctx: ToolContext): java.io.File {
    val trimmed = path.trim()
    if (trimmed == "/tmp" || trimmed.startsWith("/tmp/")) {
        val relative = trimmed.removePrefix("/tmp").trimStart('/')
        val base = java.io.File(ctx.context.cacheDir, "tmp")
        base.mkdirs()
        return if (relative.isEmpty()) base else java.io.File(base, relative)
    }
    return java.io.File(trimmed)
}
