package com.example.ui.screens.preview

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder

/**
 * Local virtual HTTPS host (`https://codeforge.local/`) for Android WebView.
 * Serves local project HTML, CSS, JS (including ES modules), JSON, images, SVG, and fonts
 * from `projectRoot` with accurate MIME types and CORS headers while preserving WebView security.
 * Fixes blank screens, broken relative paths, and file:// CORS failures on modern Android WebView.
 */
object LocalProjectWebViewHelper {

    const val VIRTUAL_HOST = "codeforge.local"
    const val VIRTUAL_ORIGIN = "https://$VIRTUAL_HOST"

    fun buildVirtualUrl(relativePath: String): String {
        val clean = relativePath.trim().replace('\\', '/').removePrefix("./").trimStart('/')
        return "$VIRTUAL_ORIGIN/$clean"
    }

    fun isVirtualUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("https://$VIRTUAL_HOST/", ignoreCase = true) ||
            url.equals("https://$VIRTUAL_HOST", ignoreCase = true)
    }

    fun interceptLocalRequest(projectRoot: File?, uri: Uri?): WebResourceResponse? {
        if (projectRoot == null || uri == null) return null
        if (!VIRTUAL_HOST.equals(uri.host, ignoreCase = true)) return null

        val rawPath = try {
            URLDecoder.decode(uri.path ?: "/", "UTF-8")
        } catch (_: Exception) {
            uri.path ?: "/"
        }
        val cleanRel = rawPath.trimStart('/').ifBlank { "index.html" }

        val canonicalRoot = try {
            projectRoot.canonicalPath
        } catch (_: Exception) {
            return notFoundResponse("Invalid project root")
        }

        var target = File(projectRoot, cleanRel)
        if (target.isDirectory) {
            target = File(target, "index.html")
        }
        if (!target.exists() && !cleanRel.contains('.')) {
            val htmlCandidate = File(projectRoot, "$cleanRel.html")
            if (htmlCandidate.exists() && htmlCandidate.isFile) {
                target = htmlCandidate
            }
        }

        val canonicalTarget = try {
            target.canonicalPath
        } catch (_: Exception) {
            return notFoundResponse("Invalid path: $cleanRel")
        }

        // Strict sandbox confinement to projectRoot
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Forbidden",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream("Forbidden path".toByteArray())
            )
        }

        if (!target.exists() || !target.isFile) {
            return notFoundResponse("Asset not found: $cleanRel")
        }

        val mime = guessMimeType(target.name)
        val encoding = if (isTextMime(mime)) "utf-8" else null
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate"
        )
        return try {
            WebResourceResponse(
                mime,
                encoding,
                200,
                "OK",
                headers,
                FileInputStream(target)
            )
        } catch (e: Exception) {
            notFoundResponse("Read error: ${e.message}")
        }
    }

    private fun notFoundResponse(message: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(message.toByteArray())
        )

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/javascript" ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "image/svg+xml"

    fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs", "cjs" -> "application/javascript"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "bmp" -> "image/bmp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wasm" -> "application/wasm"
            "xml" -> "application/xml"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
