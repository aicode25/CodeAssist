package dev.ide.android

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ide.core.project.CaprojFormat
import dev.ide.platform.ProjectFiles
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SharedProjectRootSelection
import java.io.File

/**
 * The Android host's file/SAF/FileProvider plumbing, split out of [MainActivity]: content-URI import/copy,
 * Share and "Open with", "Save As" export, APK install, and "Open in Files". Pure Android glue over an
 * [activity]. The Compose layer owns the SAF launchers (they must be `remember`ed in composition) and the
 * pending-request state; it calls these to do the byte-level work once a URI comes back.
 */
internal class AndroidFileOps(private val activity: ComponentActivity) {

    /**
     * Hand the APK at [path] to the system package installer (the OS install-confirmation UI). Only an APK the
     * IDE's own build signed is accepted ([dev.ide.android.support.BuiltApks]): the build-output path alone
     * proves nothing, since a clone or an import can put any file there. The check hashes the APK, so it runs
     * off the main thread and the installer opens after it.
     */
    fun promptInstall(path: String) {
        val name = File(path).name
        Thread({
            val built = dev.ide.ui.backend.FileActions.isBuiltApk(path) &&
                dev.ide.android.support.BuiltApks.isRecorded(File(path).toPath())
            activity.runOnUiThread {
                if (!built) {
                    Toast.makeText(activity, "CodeAssist installs only APKs its own build made. Run the app to build it.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(intent)
                }.onFailure {
                    Toast.makeText(activity, "Couldn't open the installer for $name", Toast.LENGTH_SHORT).show()
                }
            }
        }, "apk-install-check").start()
    }

    /** Copy [uri]'s bytes into a new file under [targetDir]; returns the new path or null. Blocking provider I/O
     *  (a cloud document can take seconds), so call it off the main thread. */
    fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = runCatching {
        val name = safeFileName(queryDisplayName(uri), "imported-${System.currentTimeMillis()}")
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        backend.files.createFileBytes(targetDir, name, bytes)
    }.getOrNull()

    /** Copy an inbound (`Open with`) file into a uniquely-named cache path so each hand-off is a distinct
     *  path (the import preview re-opens on each new path). Returns the real path or null. */
    fun copyInboundToCache(uri: Uri, name: String): String? = runCatching {
        val safeName = safeFileName(name, "file")
        val dest = File(activity.cacheDir, "import-${System.currentTimeMillis()}-$safeName")
        activity.contentResolver.openInputStream(uri)
            ?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /**
     * Copy the SAF tree at [treeUri] into a fresh cache directory (skipping regenerable build outputs /
     * gradle caches) and return that directory's real filesystem path. The engine's Gradle importer needs a
     * [java.nio.file.Path], but SAF hands back a `content://` tree, not a file — so we materialize a local
     * copy first. Returns null on failure, cleaning up any partial copy. Call off the main thread (it does
     * full-tree I/O).
     */
    fun copyTreeToCache(treeUri: Uri): String? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val dest = File(activity.cacheDir, "gradle-import-${System.currentTimeMillis()}")
        return runCatching {
            dest.mkdirs()
            copyDocTree(treeUri, rootDocId, dest)
            dest.absolutePath
        }.getOrElse { dest.deleteRecursively(); null }
    }

    /**
     * Recursively copy the SAF document [parentDocId] (a directory) under [treeUri] into [destDir]. [relDir] is
     * the project-relative path of [destDir] ("" at the root), for telling a module's `build/` output from a
     * source package that happens to be called `build`.
     */
    private fun copyDocTree(treeUri: Uri, parentDocId: String, destDir: File, relDir: String = "") {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val resolver = activity.contentResolver
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val childName = c.getString(nameIdx) ?: continue
                // Provider display names are untrusted: a document called `../x` must not escape the cache
                // copy. Only plain single-component names are materialized; anything else is skipped.
                if (!isSimpleFileName(childName)) continue
                val isDir = c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                val childRel = if (relDir.isEmpty()) childName else "$relDir/$childName"
                // Skip regenerable build outputs / IDE metadata to keep the copy small (the engine's importer
                // drops build/.gradle too, so leaving them out here just saves a redundant round-trip). A
                // `build` folder under `src/` is a source package, not output, and is copied.
                if (childName == ".gradle" || childName == ".idea") continue
                if (isDir && ProjectFiles.isBuildOutput(childRel, isDirectory = true, outputDirs = setOf("build"), products = false)) continue
                val childId = c.getString(idIdx)
                val child = File(destDir, childName)
                if (isDir) {
                    child.mkdirs()
                    copyDocTree(treeUri, childId, child, childRel)
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    resolver.openInputStream(docUri)?.use { input -> child.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }

    /** Copy a picked content:// file into the app cache and return its real path (keystores, screenshots). */
    fun copyUriToCache(uri: Uri): String? = runCatching {
        val name = safeFileName(queryDisplayName(uri), "picked-${System.currentTimeMillis()}")
        // Keyed by the document URI, not by the display name alone. Two screenshots from different folders
        // are both called "Screenshot_2026-09-12.png", and a name-only path made the second overwrite the
        // first and come back as a path the caller had already recorded: the publish form dropped it as a
        // duplicate while the thumbnail already on screen silently became the new image. The key keeps
        // distinct documents distinct, and the SAME document picked twice still maps to one path, which is
        // what those duplicate checks are actually for.
        val key = uri.toString().hashCode().toUInt().toString(16)
        val dest = File(activity.cacheDir, "picked-$key-$name")
        activity.contentResolver.openInputStream(uri)
            ?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** A fresh file for the camera to write a photo into, and the FileProvider URI the camera app writes through
     *  (the camera runs in another process, so it needs a grantable content:// URI, not a path). */
    fun newCameraTarget(): Pair<File, Uri> {
        val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
        // One photo at a time is all the chat needs; clearing old ones keeps the cache from filling with them.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }

    /** The first image on the clipboard as a content URI, or null. Reading the clip grants this app temporary
     *  read access to its URIs. */
    fun clipboardImageUri(): Uri? = runCatching {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (!clip.description.hasMimeType("image/*")) return@runCatching null
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
    }.getOrNull()

    /** Whether the clipboard holds an image, from its description alone (no read of the clip itself). */
    fun clipboardHasImage(): Boolean = runCatching {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClipDescription?.hasMimeType("image/*") == true
    }.getOrDefault(false)

    /**
     * Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException).
     *
     * The URI is made off the main thread: `getUriForFile` parses the provider's path config on first use and
     * canonicalizes [path], a disk walk that on shared storage (FUSE) blocked the main thread long enough for an
     * ANR in the field. Only the chooser starts back on the main thread.
     */
    fun shareFile(path: String) {
        activity.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path)) }.getOrNull()
            }
            runCatching {
                checkNotNull(uri)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeFor(path)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
            }.onFailure { Toast.makeText(activity, "Can't share this file", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Copy [src]'s bytes into the user-chosen document [uri] (the "Save As"/export destination). */
    /** Copy [src] into the user-picked [uri]. Blocking I/O: call off the main thread; the caller reports the result. */
    fun exportTo(uri: Uri, src: String): Boolean = runCatching {
        activity.contentResolver.openOutputStream(uri)
            ?.use { out -> File(src).inputStream().use { it.copyTo(out) } } != null
    }.getOrDefault(false)

    /**
     * Open the system Files app on CodeAssist's storage (served by `ProjectsDocumentsProvider`) so the user
     * can browse/manage there. Best-effort across OEM file managers: first tries to deep-link to [path]'s own
     * directory (a document-URI VIEW — DocumentsUI honors it; our doc ids are absolute paths), then the
     * provider root, then a generic Files launch, and finally explains where to look.
     */
    fun openInFiles(path: String?) {
        val auth = "${activity.packageName}.documents"
        val dirId = path?.let { p -> File(p).let { if (it.isDirectory) it else it.parentFile }?.absolutePath }
        val dirUri = dirId?.let { runCatching { DocumentsContract.buildDocumentUri(auth, it) }.getOrNull() }
        if (dirUri != null) {
            val viewDir = Intent(Intent.ACTION_VIEW)
                .setDataAndType(dirUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { activity.startActivity(viewDir); true }.getOrDefault(false)) return
        }
        val rootUri = DocumentsContract.buildRootUri(auth, AndroidIde.externalHome(activity).absolutePath)
        val viewRoot = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { activity.startActivity(viewRoot); true }.getOrDefault(false)) return
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { activity.startActivity(browse) }.getOrElse {
            Toast.makeText(activity, "Open the Files app → CodeAssist to browse your projects", Toast.LENGTH_LONG).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    fun openInBrowser(url: String) = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.getOrElse { Toast.makeText(activity, "No app to open links", Toast.LENGTH_SHORT).show() }

    /**
     * Whether this build declares the All-files permission. It is debug/profile-only (see the flavor
     * manifests), so release builds report false and the UI hides the shared-folder controls instead of
     * sending the user to a settings page that cannot grant anything.
     */
    fun canManageAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val info = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return false
        return info.requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
    }

    /** Whether Android's All-files access is granted. Direct public-storage paths need API 30+ and the grant. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /**
     * Open the system All-files access settings for this app. Some ROMs do not expose the app-specific page, so
     * fall back to the global list and then to the app's details page rather than leaving the user stuck.
     */
    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageUri = Uri.parse("package:${activity.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            if (runCatching { activity.startActivity(intent); true }.getOrDefault(false)) return
        }
        Toast.makeText(activity, "Open Settings → Apps → CodeAssist → Files and media", Toast.LENGTH_LONG).show()
    }

    /**
     * Resolve a SAF tree to the real public-storage directory it represents, persist the read/write grant for
     * later sessions, and return that directory. Only the ExternalStorageProvider has a real path; cloud
     * providers cannot be read by the engine as files, and Android's `Android/data`/`Android/obb` folders are
     * never valid shared roots. Returns null when the tree cannot be used directly.
     */
    fun prepareSharedRoot(treeUri: Uri): SharedProjectRootSelection? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        if (!canManageAllFilesAccess() || !hasAllFilesAccess()) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val volumeId = docId.substringBefore(':', missingDelimiterValue = "")
        val relative = docId.substringAfter(':', missingDelimiterValue = "")
        if (volumeId.isEmpty()) return null
        // The volume root itself is too broad: its direct children include the whole public storage tree.
        if (relative.isEmpty() || relative.startsWith('/')) return null
        if (relative.split('/').any { it == ".." || it.isEmpty() || it.contains('\u0000') }) return null
        val volume = externalStorageVolume(volumeId) ?: return null
        val dir = File(volume, relative)
        if (!dir.isDirectory || !dir.canRead() || !dir.canWrite()) return null
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val volumeCanonical = runCatching { volume.canonicalFile }.getOrNull() ?: return null
        // A symlink inside public storage must not escape the selected volume (or reach Android/data).
        if (!canonical.toPath().startsWith(volumeCanonical.toPath())) return null
        val path = canonical.absolutePath
        val normalized = path.replace('\\', '/')
        if (Regex("(^|/)Android/(data|obb)(/|$)").containsMatchIn(normalized)) return null
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { activity.contentResolver.takePersistableUriPermission(treeUri, flags) }
        return SharedProjectRootSelection(
            path = path,
            treeUri = treeUri.toString(),
            label = canonical.name.takeIf { it.isNotBlank() && it != "/" },
        )
    }

    private fun externalStorageVolume(volumeId: String): File? = when (volumeId) {
        "primary" -> Environment.getExternalStorageDirectory()
        else -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val manager = activity.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            manager.storageVolumes.firstOrNull { it.uuid?.equals(volumeId, ignoreCase = true) == true }?.directory
        }
    }

    fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * A best-effort content MIME type from the file extension (drives the share-sheet target list). An `.apk`
     * goes out as plain bytes, not as a package archive: the share sheet must not offer an installer, since
     * only [promptInstall] (APKs the IDE's own build signed) may hand an APK to one.
     */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".${CaprojFormat.EXTENSION}") -> CaprojFormat.MIME
        path.endsWith(".zip") || path.endsWith(".jar") -> "application/zip"
        path.endsWith(".txt") || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".xml") -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Whether [name] is one plain path component. Document providers (and inbound intents) supply display
     * names, and a hostile or buggy one can contain separators or `.`/`..`; those must never be joined onto a
     * target directory, or a copy/import could write outside it.
     */
    private fun isSimpleFileName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }

    /** [raw] reduced to a safe single-component file name, or [fallback] when it has no usable one. */
    private fun safeFileName(raw: String?, fallback: String): String {
        val base = raw?.trim().orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        return if (isSimpleFileName(base)) base else fallback
    }

    private companion object {
        /** The AOSP provider that exposes real public-storage paths; other SAF providers have no direct path. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
