package dev.ide.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Watches the open project for changes made outside the IDE — by Termux, a file manager, a git checkout —
 * and asks the backend to rebuild its tree when one is seen.
 *
 * Android has no portable recursive file watcher: `FileObserver` watches one directory at a time, and a
 * shared-storage tree can hold thousands. A bounded fingerprint walk is therefore the safe common denominator.
 * It runs only while the activity is in front, skips regenerable directories (build outputs, caches, VCS
 * internals), and stops after [MAX_ENTRIES] rather than turning a large project into a battery drain. The
 * backend's own writes already bump its epoch; a duplicate bump from this watcher is harmless.
 */
internal class ExternalProjectWatcher(
    private val scope: CoroutineScope,
    private val workspaceRoot: () -> String?,
    private val onChanged: () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var lastRoot: String? = null
            var lastFingerprint = 0L
            while (isActive) {
                delay(INTERVAL_MS)
                val root = workspaceRoot() ?: run { lastRoot = null; continue }
                if (root != lastRoot) {
                    lastRoot = root
                    lastFingerprint = fingerprint(root)
                    continue
                }
                val current = fingerprint(root)
                if (current != lastFingerprint) {
                    lastFingerprint = current
                    onChanged()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun fingerprint(rootPath: String): Long = runCatching {
        val root = Paths.get(rootPath)
        if (!Files.isDirectory(root)) return 0L
        var hash = 1125899906842597L
        var entries = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                if (dir != root && name in SKIPPED) return FileVisitResult.SKIP_SUBTREE
                hash = hash * 31 + name.hashCode()
                return if (++entries > MAX_ENTRIES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                hash = hash * 31 + name.hashCode()
                hash = hash * 31 + attrs.size
                hash = hash * 31 + attrs.lastModifiedTime().toMillis()
                return if (++entries > MAX_ENTRIES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        hash
    }.getOrDefault(0L)

    private companion object {
        const val INTERVAL_MS = 2_500L
        const val MAX_ENTRIES = 4_000
        val SKIPPED = setOf(".git", ".gradle", "build", ".platform", "node_modules", ".idea", "caches")
    }
}
