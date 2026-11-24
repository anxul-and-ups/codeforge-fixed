package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class UploadedFileRecord(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val uploadedAtMs: Long,
    val projectId: String = "",
    val conversationId: String = ""
) {
    val file: File get() = File(localPath)
    val exists: Boolean get() = file.exists() && file.isFile
    val isImage: Boolean
        get() = mimeType.startsWith("image/", ignoreCase = true) ||
            originalName.lowercase().let {
                it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                    it.endsWith(".webp") || it.endsWith(".svg") || it.endsWith(".gif")
            }
}

/**
 * Persistent real-filesystem storage for every file/image/asset uploaded by the user.
 *
 * Guarantees the agent ALWAYS has an exact, verified internal storage path (never an invented
 * Termux path) and can copy/import/replace project files, binary assets, or Android launcher icons
 * using the exact original bytes uploaded by the user.
 */
class UploadedFileManager(private val context: Context) {

    private val uploadsDir: File by lazy {
        File(context.filesDir, "uploaded_files").apply { mkdirs() }
    }
    private val indexFile: File by lazy {
        File(context.filesDir, "uploaded_files_index.json")
    }
    private val lock = Any()

    private val _records = MutableStateFlow<List<UploadedFileRecord>>(emptyList())
    val records: StateFlow<List<UploadedFileRecord>> = _records.asStateFlow()

    init {
        loadIndex()
    }

    /** Copies the raw bytes from SAF [uri] into persistent app storage and indexes metadata. */
    suspend fun persistUpload(
        uri: Uri,
        originalName: String,
        mimeType: String? = null,
        projectId: String = "",
        conversationId: String = ""
    ): UploadedFileRecord? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val resolvedMime = mimeType ?: try { resolver.getType(uri) } catch (_: Exception) { null }
            ?: guessMimeType(originalName)
        val id = "up_" + UUID.randomUUID().toString().take(8)
        val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "file.bin" }
        val target = File(uploadsDir, "${id}_$safeName")
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext null
            input.use { inp ->
                FileOutputStream(target).use { out ->
                    inp.copyTo(out)
                }
            }
            val record = UploadedFileRecord(
                id = id,
                originalName = originalName,
                mimeType = resolvedMime,
                localPath = target.absolutePath,
                sizeBytes = target.length(),
                uploadedAtMs = System.currentTimeMillis(),
                projectId = projectId,
                conversationId = conversationId
            )
            synchronized(lock) {
                val next = (listOf(record) + _records.value).filter { it.exists }.take(120)
                _records.value = next
                saveIndex(next)
            }
            record
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun listForSession(projectId: String, conversationId: String? = null): List<UploadedFileRecord> {
        val alive = _records.value.filter { it.exists }
        val matching = alive.filter { rec ->
            (conversationId != null && rec.conversationId == conversationId) ||
                (projectId.isNotBlank() && rec.projectId == projectId)
        }
        return if (matching.isNotEmpty()) matching else alive.take(15)
    }

    fun resolve(idOrName: String, projectId: String = ""): UploadedFileRecord? {
        val query = idOrName.trim()
        val alive = _records.value.filter { it.exists }
        if (query.isBlank()) {
            return alive.firstOrNull { it.projectId == projectId } ?: alive.firstOrNull()
        }
        // Exact ID match first
        alive.firstOrNull { it.id.equals(query, ignoreCase = true) }?.let { return it }
        // Exact local path match
        alive.firstOrNull { it.localPath == query }?.let { return it }
        // Exact original filename match (prefer active project)
        alive.firstOrNull { it.projectId == projectId && it.originalName.equals(query, ignoreCase = true) }?.let { return it }
        alive.firstOrNull { it.originalName.equals(query, ignoreCase = true) }?.let { return it }
        // Partial filename match
        alive.firstOrNull { it.originalName.contains(query, ignoreCase = true) }?.let { return it }
        return null
    }

    /** Copies the EXACT raw bytes of [record] into [destRelativePath] inside [projectRoot]. */
    suspend fun copyIntoProject(
        record: UploadedFileRecord,
        projectRoot: File,
        destRelativePath: String
    ): File = withContext(Dispatchers.IO) {
        if (!record.exists) throw IllegalStateException("Uploaded file '${record.originalName}' no longer exists on disk.")
        val cleanRel = destRelativePath.trim().removePrefix("./").trimStart('/')
        if (cleanRel.isBlank()) throw IllegalArgumentException("Destination path cannot be blank")
        val target = File(projectRoot, cleanRel)
        val canonicalRoot = projectRoot.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            throw SecurityException("Path traversal blocked: $destRelativePath")
        }
        target.parentFile?.mkdirs()
        record.file.copyTo(target, overwrite = true)
        target
    }

    /**
     * Replaces an Android project's launcher icons using the EXACT uploaded image bytes:
     * - Automatically trims flat white outer padding if present (like rounded icon cards on white canvas)
     * - Writes crisp adaptive foreground PNG + background XML + adaptive icon XMLs
     * - Generates all 5 density `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` & circular `ic_launcher_round.png`
     * - Removes conflicting `.webp` launcher icons in those mipmap folders
     */
    suspend fun replaceAndroidLauncherIcons(
        projectRoot: File,
        sourceImageFile: File
    ): List<String> = withContext(Dispatchers.IO) {
        if (!sourceImageFile.exists()) throw IllegalStateException("Source image file not found: ${sourceImageFile.absolutePath}")
        val rawBitmap = BitmapFactory.decodeFile(sourceImageFile.absolutePath)
            ?: throw IllegalStateException("Could not decode image '${sourceImageFile.name}'. Provide a valid PNG, JPG, or WEBP.")

        // Locate Android res directory (supports standard app/src/main/res or src/main/res)
        val resDir = listOf(
            File(projectRoot, "app/src/main/res"),
            File(projectRoot, "src/main/res"),
            File(projectRoot, "res")
        ).firstOrNull { it.exists() && it.isDirectory }
            ?: File(projectRoot, "app/src/main/res").apply { mkdirs() }

        val trimmed = trimOuterWhiteBorder(rawBitmap)
        val squareBitmap = centerCropSquare(trimmed, 512)
        val updatedRelativePaths = mutableListOf<String>()

        fun recordRel(f: File) {
            updatedRelativePaths.add(f.relativeTo(projectRoot).path.replace(File.separatorChar, '/'))
        }

        // 1. Save high-res foreground artwork
        val drawableNodpi = File(resDir, "drawable-nodpi").apply { mkdirs() }
        val drawableDir = File(resDir, "drawable").apply { mkdirs() }

        // Check whether project references ic_launcher_fg_img or ic_launcher_foreground_img
        val anydpiDir = File(resDir, "mipmap-anydpi-v26").apply { mkdirs() }
        val fgImgFile = File(drawableNodpi, "ic_launcher_fg_img.png")
        savePng(squareBitmap, fgImgFile)
        recordRel(fgImgFile)

        // Foreground inset wrapper (centers safely in adaptive 108dp canvas)
        val fgXml = File(drawableDir, "ic_launcher_foreground.xml")
        fgXml.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                <item
                    android:width="72dp"
                    android:height="72dp"
                    android:gravity="center"
                    android:drawable="@drawable/ic_launcher_fg_img" />
            </layer-list>
            """.trimIndent() + "\n"
        )
        recordRel(fgXml)

        // Sample dominant dark corner pixel from trimmed artwork for seamless adaptive background
        val bgHex = sampleEdgeBackgroundHex(squareBitmap)
        val bgXml = File(drawableDir, "ic_launcher_background.xml")
        bgXml.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path
                    android:fillColor="$bgHex"
                    android:pathData="M0,0h108v108h-108z" />
            </vector>
            """.trimIndent() + "\n"
        )
        recordRel(bgXml)

        val adaptiveXmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background" />
                <foreground android:drawable="@drawable/ic_launcher_foreground" />
            </adaptive-icon>
        """.trimIndent() + "\n"

        val adaptiveMain = File(anydpiDir, "ic_launcher.xml").apply { writeText(adaptiveXmlContent) }
        val adaptiveRound = File(anydpiDir, "ic_launcher_round.xml").apply { writeText(adaptiveXmlContent) }
        recordRel(adaptiveMain)
        recordRel(adaptiveRound)

        // 2. Generate all 5 density legacy square & circular round PNG icons
        val densities = listOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )
        for ((folder, px) in densities) {
            val dir = File(resDir, folder).apply { mkdirs() }
            // Delete conflicting .webp launcher icons
            File(dir, "ic_launcher.webp").takeIf { it.exists() }?.delete()
            File(dir, "ic_launcher_round.webp").takeIf { it.exists() }?.delete()

            val scaled = Bitmap.createScaledBitmap(squareBitmap, px, px, true)
            val squareOut = File(dir, "ic_launcher.png")
            savePng(scaled, squareOut)
            recordRel(squareOut)

            val roundBmp = makeCircularBitmap(scaled)
            val roundOut = File(dir, "ic_launcher_round.png")
            savePng(roundBmp, roundOut)
            recordRel(roundOut)

            if (roundBmp != scaled) roundBmp.recycle()
            if (scaled != squareBitmap) scaled.recycle()
        }

        if (squareBitmap != trimmed) squareBitmap.recycle()
        if (trimmed != rawBitmap) trimmed.recycle()
        rawBitmap.recycle()

        updatedRelativePaths
    }

    fun buildAgentContextBlock(projectId: String, conversationId: String? = null): String {
        val list = listForSession(projectId, conversationId)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("UPLOADED USER FILES (REAL INTERNAL STORAGE PATHS — NEVER INVENT TERMUX PATHS):")
        sb.appendLine("Use `import_uploaded_file(file_id_or_name, dest_path)` to copy exact bytes into the project, or `replace_app_icon(file_id_or_name)` for Android app icons.")
        for (r in list.take(12)) {
            val kb = (r.sizeBytes / 1024L).coerceAtLeast(1L)
            sb.appendLine("- id=`${r.id}` | name=`${r.originalName}` | mime=`${r.mimeType}` | size=${kb}KB | path=`${r.localPath}`")
        }
        return sb.toString().trim()
    }

    private fun savePng(bitmap: Bitmap, dest: File) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun trimOuterWhiteBorder(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return src
        // Only trim if all 4 corners are nearly pure white or fully transparent
        val corners = listOf(src.getPixel(0, 0), src.getPixel(w - 1, 0), src.getPixel(0, h - 1), src.getPixel(w - 1, h - 1))
        val isBgPixel: (Int) -> Boolean = { px ->
            val a = Color.alpha(px)
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            a < 20 || (r > 240 && g > 240 && b > 240)
        }
        if (!corners.all(isBgPixel)) return src

        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        val step = (minOf(w, h) / 256).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (!isBgPixel(src.getPixel(x, y))) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX <= minX || maxY <= minY) return src
        val cropW = (maxX - minX + 1).coerceIn(16, w)
        val cropH = (maxY - minY + 1).coerceIn(16, h)
        return Bitmap.createBitmap(src, minX, minY, cropW, cropH)
    }

    private fun centerCropSquare(src: Bitmap, targetSize: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (cropped != src && cropped != scaled) cropped.recycle()
        return scaled
    }

    private fun makeCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(RectF(rect), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, rect, rect, paint)
        return out
    }

    private fun sampleEdgeBackgroundHex(bmp: Bitmap): String {
        return try {
            val px = bmp.getPixel(bmp.width / 2, (bmp.height * 0.08f).toInt().coerceIn(0, bmp.height - 1))
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            // Keep adaptive background dark & cohesive if image has white/transparent corner
            if (Color.alpha(px) < 40 || (r > 235 && g > 235 && b > 235)) "#0B0B0D"
            else String.format("#%02X%02X%02X", r, g, b)
        } catch (_: Exception) {
            "#0B0B0D"
        }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "kt", "java", "py", "ts", "tsx", "xml", "md", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val arr = JSONObject(indexFile.readText()).optJSONArray("uploads") ?: return
            val list = ArrayList<UploadedFileRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rec = UploadedFileRecord(
                    id = o.optString("id"),
                    originalName = o.optString("originalName"),
                    mimeType = o.optString("mimeType"),
                    localPath = o.optString("localPath"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    uploadedAtMs = o.optLong("uploadedAtMs", 0L),
                    projectId = o.optString("projectId"),
                    conversationId = o.optString("conversationId")
                )
                if (rec.exists) list.add(rec)
            }
            _records.value = list
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun saveIndex(list: List<UploadedFileRecord>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("originalName", r.originalName)
                        .put("mimeType", r.mimeType)
                        .put("localPath", r.localPath)
                        .put("sizeBytes", r.sizeBytes)
                        .put("uploadedAtMs", r.uploadedAtMs)
                        .put("projectId", r.projectId)
                        .put("conversationId", r.conversationId)
                )
            }
            indexFile.writeText(JSONObject().put("uploads", arr).toString())
        } catch (_: Exception) {
            // ignore
        }
    }
}
