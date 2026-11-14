package org.bin.apk.herder.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The downloadable llama.cpp backend.
 *
 * Herder's core trick, inherited from the PR-14 experiment: at targetSdk 28
 * an app may execute binaries stored in its own private storage. The backend
 * is therefore NOT bundled in the APK — it is downloaded from the llama.cpp
 * project's own releases, verified against the SHA-256 pin compiled in
 * below, extracted, and run as a separate process. Updating the backend
 * later means changing the pin, not the architecture.
 *
 * (This is the checksum pinning llama-android lacked.)
 */
object Backend {
    // Bumped b11798 → b12110 at Brian's call on 2026-11-08: b12110
    // (published 2026-11-04) carries ggml 0.29.0 (the v0.9.0 line) —
    // the jump worth taking — while landing just before the b12121
    // same-day build. Both tarball layouts verified unchanged from
    // b11798 before the pin swap (standard flat, Snapdragon bin/+lib/).
    //
    // v0.2.2 finding (onn gate, 2026-11-09): the SNAPDRAGON tarball is
    // built for high-end Kryo cores — its single libggml-cpu.so carries
    // 1,328 UNCONDITIONAL udot/sdot (dot-product) instructions with no
    // runtime dispatch, so on an SoC without FEAT_DOTPROD (Snapdragon
    // 680 / Kryo 265: /proc/cpuinfo Features has no 'asimddp') the
    // binary dies with SIGILL the moment llama-server loads its own
    // libraries — under the app's linker namespace it surfaces as a
    // misleading "libOpenCL.so not found" loader error. The b11798
    // Snapdragon build has the identical defect (proven by direct
    // on-device repro of both builds); its one "successful" onn run in
    // v0.1.7 was in fact the Standard tree (a layout bug that night
    // pointed the server at it). The STANDARD tarball instead ships
    // seven runtime-selected libggml-cpu variants (armv8.0 with ZERO
    // dot-product instructions up to armv9.2) and runs everywhere —
    // verified on the onn. Herder therefore defaults to STANDARD on
    // every device; the Snapdragon variant remains selectable only as
    // an experiment for CPUs that actually have dot-product support.
    const val BUILD_TAG = "b12110"

    /** The semantic version the pinned build belongs to: b12110 IS
     *  llama.cpp v0.9.0 (verified on slate, where the b12110 binary
     *  itself reports "version: 0.9.0-dev"). Shown on the Backend
     *  card's title line, right-flushed (v0.2.8 cosmetic). */
    const val BUILD_VERSION = "v0.9.0"

    data class Variant(
        val id: String,
        val label: String,
        /** Pin for the compiled-in build ([BUILD_TAG]) only. A custom
         *  build tag has no pin — it is installed trust-on-first-use. */
        val sha256: String,
        /** Byte size of the pinned build's tarball (progress totals,
         *  button labels). A custom tag's size is known only from the
         *  download itself. */
        val sizeBytes: Long,
    ) {
        /** Release asset name for [tag]. For the pinned tag these are
         *  exactly the names ggml ships (verified against the releases). */
        fun fileName(tag: String): String =
            if (id == SNAPDRAGON.id) "llama-$tag-bin-android-arm64-snapdragon.tar.gz"
            else "llama-$tag-bin-android-arm64.tar.gz"

        fun url(tag: String): String =
            "https://github.com/ggml-org/llama.cpp/releases/download/$tag/${fileName(tag)}"

        /** Where llama-server sits inside the extracted tree for
         *  [tag] — the two release tarballs do NOT share a layout:
         *  the standard tree is flat (binaries at the top), the
         *  Snapdragon tarball is a full install tree (bin/ + lib/). */
        fun serverRelPath(tag: String): String =
            if (id == SNAPDRAGON.id) "llama-$tag/bin/llama-server" else "llama-$tag/llama-server"
    }

    val STANDARD = Variant(
        id = "arm64",
        label = "Standard ARM64",
        sha256 = "44462ddcc09a841942e5cfe76554240ed6d7763bdf8538232075eb0ffd46ccbe",
        sizeBytes = 79_929_855L,
    )

    val SNAPDRAGON = Variant(
        id = "snapdragon",
        label = "Snapdragon (Hexagon) — experimental",
        sha256 = "69b86654fd8358b97b1a6665b54fcfdfb856c29e5958d41a31c461101626a6ac",
        sizeBytes = 65_027_754L,
    )

    val VARIANTS = listOf(STANDARD, SNAPDRAGON)

    /**
     * The build tag the Standard variant currently installs and runs:
     * the compiled-in pin, unless an Expert custom build tag has been
     * set (Settings, v0.3.0). Custom tags apply to Standard ONLY —
     * the Snapdragon variant always stays on the pinned build.
     */
    fun activeTag(context: Context): String =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY_BACKEND_TAG, null)
            ?.takeIf { it.isNotBlank() } ?: BUILD_TAG

    /** The tag governing [variant]'s tree on disk. */
    fun tagFor(context: Context, variant: Variant): String =
        if (variant.id == STANDARD.id) activeTag(context) else BUILD_TAG

    /** The pinned backend is a 64-bit arm64 program; a 32-bit-only device can never exec it. */
    fun supportsBackend(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /**
     * Always STANDARD. The Snapdragon-branded build is not a safe
     * default even on Snapdragon hardware (see the v0.2.2 finding
     * above): its CPU library assumes dot-product instructions that
     * whole Kryo generations lack. The Standard build detects the CPU
     * itself and picks a matching library at runtime.
     */
    fun detectDefaultVariant(): Variant = STANDARD

    fun rootDir(context: Context): File = File(context.filesDir, "backend")

    fun variantDir(context: Context, variant: Variant): File =
        File(rootDir(context), variant.id)

    fun serverBinary(context: Context, variant: Variant): File =
        File(variantDir(context, variant), variant.serverRelPath(tagFor(context, variant)))

    /** Dirs the dynamic linker must search: the binary's own dir (the
     *  standard tree keeps its .so files beside the executables) plus the
     *  install tree's lib/ (the Snapdragon layout) when it exists. */
    fun libraryPath(context: Context, variant: Variant): String {
        val binary = serverBinary(context, variant)
        val treeRoot = File(variantDir(context, variant), "llama-${tagFor(context, variant)}")
        return listOf(binary.parentFile, File(treeRoot, "lib"))
            .filterNotNull()
            .filter { it.isDirectory }
            .joinToString(":") { it.absolutePath }
    }

    /**
     * Which installed backend a server start would use. Prefers the
     * default variant when present (v0.2.3): a leftover experimental
     * tree from an older install must not silently win the start just
     * because the default isn't installed yet — the gate run on the
     * onn had exactly that trap (an unusable Snapdragon tree sat
     * installed while Standard was still missing).
     */
    fun installedVariant(context: Context): Variant? {
        val fallback = detectDefaultVariant()
        if (serverBinary(context, fallback).exists()) return fallback
        return VARIANTS.firstOrNull { serverBinary(context, it).exists() }
    }

    fun isInstalled(context: Context, variant: Variant): Boolean =
        serverBinary(context, variant).exists()

    // ---- Cleanup of old downloads (v0.2.6, Brian's ask)
    //
    // A backend tree is only ever addressed as llama-<active tag>, so a
    // tree from any other tag is unreachable the moment the active tag
    // moves (a pin bump, or an Expert custom build taking over) — yet
    // nothing ever deleted one: by the end of the b11798→b12110
    // gates the test tablet held four trees ≈ 1 GB. Same for a leftover
    // tarball from an interrupted install, a staged custom download
    // that was never confirmed, and (in Models) a partial model
    // download, which the downloader restarts from zero anyway.
    // All dead by construction, so pruning needs no judgment calls.

    private fun isStaleTree(context: Context, variant: Variant, child: File): Boolean =
        child.isDirectory && child.name.startsWith("llama-") &&
            child.name != "llama-${tagFor(context, variant)}"

    private fun isStaleFile(child: File): Boolean =
        child.isFile && (child.name.endsWith(".tar.gz") || child.name.endsWith(".tar.gz.staged"))

    private fun treeBytes(file: File): Long =
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else file.length()

    /** Bytes held by backend downloads no code path can use again. */
    fun staleDownloadBytes(context: Context): Long {
        var total = 0L
        for (variant in VARIANTS) {
            variantDir(context, variant).listFiles()?.forEach { child ->
                when {
                    isStaleTree(context, variant, child) -> total += treeBytes(child)
                    isStaleFile(child) -> total += child.length()
                }
            }
        }
        return total
    }

    /** Delete what [staleDownloadBytes] counts. Returns bytes freed. */
    fun pruneStaleDownloads(context: Context): Long {
        var freed = 0L
        for (variant in VARIANTS) {
            variantDir(context, variant).listFiles()?.forEach { child ->
                when {
                    isStaleTree(context, variant, child) -> {
                        freed += treeBytes(child)
                        child.deleteRecursively()
                    }
                    isStaleFile(child) -> {
                        freed += child.length()
                        child.delete()
                    }
                }
            }
        }
        return freed
    }

    /** Download -> verify SHA-256 against the pin -> extract -> mark executables.
     *  This is the PINNED build's path, always: a custom Expert tag goes
     *  through the two-phase [downloadCustomBuild]/[installStagedBuild]. */
    suspend fun install(
        context: Context,
        variant: Variant,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = variantDir(context, variant)
            dir.mkdirs()
            val tarball = File(dir, variant.fileName(BUILD_TAG))

            // Download — manual redirect loop: HttpURLConnection's
            // automatic following dies permanently on a 308 (observed
            // on-device, 2026-11-09: the GitHub release URL answered
            // 308 exactly once and the download failed with it), so
            // redirects are followed by hand, same protocol rules.
            var connection = openGet(variant.url(BUILD_TAG))
            var hops = 0
            while (connection.responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || ++hops > MAX_REDIRECTS) {
                    throw IllegalStateException("Download failed: bad redirect chain")
                }
                connection = openGet(URL(URL(variant.url(BUILD_TAG)), location).toString())
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = if (connection.contentLengthLong > 0) connection.contentLengthLong else variant.sizeBytes
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tarball).use { output ->
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

            // Verify against the compiled-in pin
            val actual = sha256(tarball)
            if (!actual.equals(variant.sha256, ignoreCase = true)) {
                tarball.delete()
                throw IllegalStateException(
                    "Checksum mismatch — refusing to install. Expected ${variant.sha256.take(16)}…, got ${actual.take(16)}…"
                )
            }

            // Extract (the tarball is a plain ustar archive: dirs + regular files).
            // The tarball is deleted in a finally: a failed extraction
            // used to leave it behind in the variant dir, where only a
            // later manual cleanup would ever have reclaimed it.
            try {
                extractTarGz(tarball, dir)
            } finally {
                tarball.delete()
            }

            markExecutables(dir)
            val binary = File(dir, variant.serverRelPath(BUILD_TAG))
            if (!binary.exists()) throw IllegalStateException("Extraction finished but llama-server is missing")
            binary
        }
    }

    /** Executable bits for every binary in an extracted tree. */
    private fun markExecutables(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.endsWith(".so")) {
                file.setExecutable(true, false)
            }
        }
    }

    /**
     * A custom build's tarball, downloaded and hashed, awaiting the
     * user's trust-on-first-use decision (Expert setting, v0.3.0).
     * Nothing has been extracted yet; [tarball] is the staged file.
     */
    data class StagedBuild(
        val tag: String,
        val tarball: File,
        val sha256: String,
        val sizeBytes: Long,
    )

    /**
     * Phase one of a custom build install: download the Standard
     * tarball for [tag] and hash it, WITHOUT extracting. There is no
     * compiled-in pin for a custom tag — the returned [StagedBuild]
     * carries the hash of what actually arrived, and the UI must get
     * the user's explicit confirmation of that hash before phase two
     * ([installStagedBuild]) runs. The staged file carries a
     * ".staged" suffix so an unconfirmed download is never mistaken
     * for an installed tree (and the stale-download sweep can reclaim
     * it if the confirmation never comes).
     */
    suspend fun downloadCustomBuild(
        context: Context,
        tag: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<StagedBuild> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = variantDir(context, STANDARD)
            dir.mkdirs()
            val staged = File(dir, STANDARD.fileName(tag) + ".staged")
            // Same manual redirect loop as the pinned install (308).
            var connection = openGet(STANDARD.url(tag))
            var hops = 0
            while (connection.responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || ++hops > MAX_REDIRECTS) {
                    throw IllegalStateException("Download failed: bad redirect chain")
                }
                connection = openGet(URL(URL(STANDARD.url(tag)), location).toString())
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            // No pin, no known size: the total is whatever the server
            // declares, or 0 (progress shows bytes only).
            val total = if (connection.contentLengthLong > 0) connection.contentLengthLong else 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(staged).use { output ->
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
            StagedBuild(tag, staged, sha256(staged), staged.length())
        }
    }

    /** Phase two: extract a confirmed [StagedBuild] into its tree. */
    suspend fun installStagedBuild(context: Context, staged: StagedBuild): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = variantDir(context, STANDARD)
                if (!staged.tarball.exists()) {
                    throw IllegalStateException("The staged download is gone — download the custom build again")
                }
                try {
                    extractTarGz(staged.tarball, dir)
                } finally {
                    staged.tarball.delete()
                }
                markExecutables(dir)
                val binary = File(dir, STANDARD.serverRelPath(staged.tag))
                if (!binary.exists()) throw IllegalStateException("Extraction finished but llama-server is missing")
                binary
            }
        }

    /** Throw a staged download away (the user declined its hash). */
    fun discardStagedBuild(staged: StagedBuild) {
        staged.tarball.delete()
    }

    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    const val MAX_REDIRECTS = 8

    /** A GET connection that does NOT auto-follow redirects — callers
     *  loop on [REDIRECT_CODES] themselves (see install). */
    fun openGet(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            connect()
        }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Minimal ustar reader — sufficient for the llama.cpp release tarballs (verified: only dir/file entries, short names, no pax headers). */
    private fun extractTarGz(tarball: File, destDir: File) {
        val destRoot = destDir.canonicalFile
        GZIPInputStream(BufferedInputStream(FileInputStream(tarball))).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).toString(Charsets.UTF_8).trim('\u0000', ' ')
                val prefix = header.copyOfRange(345, 500).toString(Charsets.UTF_8).trim('\u0000', ' ')
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                val sizeText = header.copyOfRange(124, 136).toString(Charsets.UTF_8).trim('\u0000', ' ')
                val size = if (sizeText.isEmpty()) 0L else sizeText.toLong(8)
                val type = header[156].toInt().toChar()
                if (fullName.isEmpty()) {
                    skipFully(input, padded(size))
                    continue
                }
                val target = File(destRoot, fullName).canonicalFile
                require(target.path == destRoot.path || target.path.startsWith(destRoot.path + File.separator)) {
                    "Tar entry escapes destination: $fullName"
                }
                when (type) {
                    '5' -> {
                        target.mkdirs()
                        skipFully(input, padded(size))
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            copyExactly(input, output, size)
                        }
                        skipFully(input, padded(size) - size)
                    }
                    else -> skipFully(input, padded(size))
                }
            }
        }
    }

    private fun padded(size: Long): Long = ((size + 511) / 512) * 512

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset > 0
            offset += read
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun copyExactly(input: java.io.InputStream, output: FileOutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("Unexpected end of archive")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
