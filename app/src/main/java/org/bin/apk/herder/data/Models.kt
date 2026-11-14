package org.bin.apk.herder.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import org.bin.apk.herder.engine.Backend

/** A downloadable model preset. URLs, byte sizes and SHA-256 hashes verified against the Hugging Face LFS metadata on 2026-11-10. */
data class ModelPreset(
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val note: String,
)

object Models {
    val PRESETS = listOf(
        ModelPreset(
            name = "Qwen3.5 0.8B",
            fileName = "Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
            sizeBytes = 558_772_480L,
            sha256 = "3177ebd67afe4438374da19e690bc1b98756f7e0fea9240e1be404336156a7b5",
            note = "Small and quick — a first test on any device",
        ),
        ModelPreset(
            name = "Qwen3.5 2B",
            fileName = "Qwen3.5-2B-UD-Q4_K_XL.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q4_K_XL.gguf",
            sizeBytes = 1_339_752_704L,
            sha256 = "0af96165ea615bea39a04118d63f0b6d35908aea850ee4a51aa6151d851b8b35",
            note = "The balanced pick for an 8 GB tablet",
        ),
        ModelPreset(
            name = "Qwen3.5 4B",
            fileName = "Qwen3.5-4B-UD-Q4_K_XL.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-UD-Q4_K_XL.gguf",
            sizeBytes = 2_912_109_728L,
            sha256 = "b252c5610a42ca82d20fe2a12813e9d069eed89292907e26c783eeb0bc961bc7",
            note = "Best quality of the three — wants RAM headroom",
        ),
    )

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun localModels(context: Context): List<File> =
        modelsDir(context).listFiles { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun presetFile(context: Context, preset: ModelPreset): File = File(modelsDir(context), preset.fileName)

    fun isDownloaded(context: Context, preset: ModelPreset): Boolean {
        val file = presetFile(context, preset)
        return file.exists() && file.length() == preset.sizeBytes
    }

    suspend fun download(
        context: Context,
        preset: ModelPreset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = presetFile(context, preset)
            val partial = File(target.parentFile, target.name + ".part")
            // Manual redirect loop — HttpURLConnection's automatic
            // following fails on 308; see Backend.install.
            var connection = Backend.openGet(preset.url)
            var hops = 0
            while (connection.responseCode in Backend.REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || ++hops > Backend.MAX_REDIRECTS) {
                    throw IllegalStateException("Download failed: bad redirect chain")
                }
                connection = Backend.openGet(URL(URL(preset.url), location).toString())
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = if (connection.contentLengthLong > 0) connection.contentLengthLong else preset.sizeBytes
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (partial.length() != preset.sizeBytes) {
                val got = partial.length()
                partial.delete()
                throw IllegalStateException("Download incomplete: got $got of ${preset.sizeBytes} bytes")
            }
            // Verify content, not just length: the backend has been
            // SHA-256-pinned since the beginning; models get the same
            // treatment (v0.3.0 review pass) — a same-length wrong file
            // is indistinguishable by size alone. Mirrors the check in
            // Backend.install.
            val actual = Backend.sha256(partial)
            if (!actual.equals(preset.sha256, ignoreCase = true)) {
                partial.delete()
                throw IllegalStateException(
                    "Checksum mismatch — refusing to install. Expected ${preset.sha256.take(16)}…, got ${actual.take(16)}…"
                )
            }
            // No delete-before-rename: renameTo on the same filesystem
            // replaces the target atomically, and if it fails the old
            // model is still there.
            if (!partial.renameTo(target)) throw IllegalStateException("Could not move the downloaded model into place")
            target
        }
    }

    /** Bytes held by partial model downloads. A .part is never resumed
     *  (the downloader starts over), so it is dead weight by construction. */
    fun stalePartBytes(context: Context): Long =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.sumOf { it.length() } ?: 0L

    /** Delete partial model downloads. Returns bytes freed. */
    fun pruneParts(context: Context): Long {
        var freed = 0L
        modelsDir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }

    /** Copy a GGUF the user picked from device storage into Herder's model folder. */
    suspend fun importFromUri(context: Context, uri: Uri, displayName: String?): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                // The display name comes from the document provider and
                // is not trusted as a path: reduce it to its final
                // component before use, so a name like
                // "../../server.log.gguf" lands inside the models
                // folder under a plain file name. Anything that
                // doesn't reduce to a *.gguf name gets the generated
                // name instead.
                // Imports are NOT hash-verified (unlike preset
                // downloads): there is no expected hash for a file the
                // user picked — the name reduction above is the whole
                // defence, and it only confines where the copy lands.
                val reduced = displayName?.let { File(it).name }?.takeIf { it.isNotEmpty() }
                val name = (reduced?.takeIf { it.endsWith(".gguf", ignoreCase = true) }
                    ?: "imported-${System.currentTimeMillis()}.gguf")
                val target = File(modelsDir(context), name)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the picked file")
                input.use { stream ->
                    FileOutputStream(target).use { output -> stream.copyTo(output, 256 * 1024) }
                }
                target
            }
        }

    /**
     * A model file's name, tidied for display only (v0.2.8 cosmetic —
     * the file on disk keeps its real name): the ".gguf" extension is
     * dropped, and the dash right after the model name becomes a
     * space. "Qwen3.5-2B-UD-Q4_K_XL.gguf" reads as
     * "Qwen3.5 2B-UD-Q4_K_XL".
     */
    fun displayName(fileName: String): String {
        val base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")
        val dash = base.indexOf('-')
        return if (dash > 0) base.substring(0, dash) + " " + base.substring(dash + 1) else base
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
