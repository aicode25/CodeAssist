package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared real-path roots: registration, listing in place, symlink handling, and registry-only removal. */
class SharedProjectRootTest {

    private fun createProject(root: java.nio.file.Path, name: String): java.nio.file.Path =
        ProjectManager.desktop(root)
            .create("java-console", mapOf("name" to name, "packageName" to "com.acme.shared"))
            .use { it.workspaceRoot }

    @Test
    fun registersARealRootAndListsItsProjectsInPlace() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val project = createProject(shared, "Shared Demo")
            val manager = ProjectManager.desktop(home.resolve("projects"))

            val registered = manager.registerSharedProjectRoot(shared.toString(), treeUri = "content://tree", label = "Shared")
            assertEquals(shared.toRealPath().toString(), registered.path)
            assertEquals("Shared", registered.label)

            val listed = manager.list().single()
            assertEquals("Shared Demo", listed.name)
            assertEquals(project.toRealPath().toString(), listed.rootPath)
            manager.open(listed.rootPath).use { ide ->
                assertEquals("Shared Demo", ide.projectDisplayName())
            }

            // A second manager on the same prefs sees the registration (it survives a restart).
            val restarted = ProjectManager.desktop(home.resolve("projects"))
            assertEquals(listOf(registered.path), restarted.sharedProjectRoots().map { it.path })
        }
    }

    @Test
    fun removalForgetsTheRootButNeverDeletesProjects() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val project = createProject(shared, "Keep Me")
            val manager = ProjectManager.desktop(home.resolve("projects"))
            val registered = manager.registerSharedProjectRoot(shared.toString())

            assertTrue(manager.unregisterSharedProjectRoot(registered.path))
            assertTrue(Files.exists(project), "removing a shared root must not delete its projects")
            assertTrue(manager.list().isEmpty())
            assertFalse(manager.unregisterSharedProjectRoot(registered.path), "the second removal is a no-op")
        }
    }

    @Test
    fun resolvesSymlinkedRootsAndSkipsSymlinkedProjectChildren() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val project = createProject(shared, "Real One")
            val manager = ProjectManager.desktop(home.resolve("projects"))

            val link = base.resolve("shared-link")
            val linked = runCatching { Files.createSymbolicLink(link, shared); true }.getOrDefault(false)
            if (linked) {
                val registered = manager.registerSharedProjectRoot(link.toString())
                assertEquals(shared.toRealPath().toString(), registered.path)
            } else {
                manager.registerSharedProjectRoot(shared.toString())
            }

            // A symlinked child must not smuggle a second entry into the picker.
            runCatching { Files.createSymbolicLink(shared.resolve("linked-project"), project) }
            val listed = manager.list()
            assertEquals(1, listed.size, "symlinked projects are not listed: $listed")
            assertEquals(project.toRealPath().toString(), listed.single().rootPath)
        }
    }

    @Test
    fun refusesTheAppsOwnHomeAsASharedRoot() = withTempDir("shared-home") { home ->
        val manager = ProjectManager.desktop(home.resolve("projects"))
        assertFailsWith<IllegalArgumentException> {
            manager.registerSharedProjectRoot(home.toString())
        }
        assertTrue(manager.sharedProjectRoots().isEmpty())
    }

    @Test
    fun refusesASharedRootThatContainsTheAppsHome() = withTempDir("shared-home") { home ->
        val manager = ProjectManager.desktop(home.resolve("projects"))
        // An ancestor of the app home (for example the whole public-storage volume) would make its direct
        // children deletable/renamable, including Android/data and the app's own directory.
        assertFailsWith<IllegalArgumentException> {
            manager.registerSharedProjectRoot(home.parent.toString())
        }
        assertTrue(manager.sharedProjectRoots().isEmpty())
    }

    @Test
    fun deletingASharedProjectDoesNotCreateOrTouchADotTrash() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val project = createProject(shared, "Delete Me")
            val userTrash = shared.resolve(".trash").also { Files.createDirectories(it) }
            val userFile = userTrash.resolve("keep.txt").also { Files.writeString(it, "user data") }
            val manager = ProjectManager.desktop(home.resolve("projects"))
            manager.registerSharedProjectRoot(shared.toString())

            assertTrue(manager.delete(project.toString()))
            assertFalse(Files.exists(project), "the selected project is gone")
            assertTrue(Files.exists(userFile), "a pre-existing .trash in the user's folder is untouched")

            manager.purgeTrash()
            assertTrue(Files.exists(userFile), "startup trash cleanup never touches a shared root")
        }
    }

    @Test
    fun ignoresARegisteredRootReplacedByASymlinkToTheAppsHome() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val manager = ProjectManager.desktop(home.resolve("projects"))
            manager.registerSharedProjectRoot(shared.toString())
            // A project in the app home that must never leak into the picker through a swapped root.
            createProject(home, "Private Project")

            val swapped = runCatching {
                Files.delete(shared)
                Files.createSymbolicLink(shared, home)
                true
            }.getOrDefault(false)
            if (swapped) {
                assertTrue(manager.list().isEmpty(), "a root replaced by a symlink to the app home is ignored")
            }
        }
    }

    @Test
    fun duplicateRegistrationUpdatesOneEntry() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val manager = ProjectManager.desktop(home.resolve("projects"))
            val first = manager.registerSharedProjectRoot(shared.toString(), label = "First")
            val second = manager.registerSharedProjectRoot(shared.toString(), label = "Second")

            assertEquals(first.addedAt, second.addedAt)
            val roots = manager.sharedProjectRoots()
            assertEquals(1, roots.size)
            assertEquals("Second", roots.single().label)
            assertNotNull(roots.single().path)
        }
    }

    @Test
    fun listsAndAdoptsAnExistingToolProjectInPlace() = withTempDir("shared-home") { home ->
        withTempDir("shared-data") { base ->
            val shared = base.resolve("shared").also { Files.createDirectories(it) }
            val project = shared.resolve("TermuxApp").also { Files.createDirectories(it.resolve("app/src/main")) }
            Files.writeString(project.resolve("settings.gradle"), "include ':app'\n")
            Files.writeString(project.resolve("build.gradle"), "// root build\n")
            val source = project.resolve("app/src/main/Main.kt")
            Files.writeString(source, "fun main() = Unit\n")
            // A random directory is not offered as a project just because it sits under a shared root.
            Files.createDirectories(shared.resolve("downloads-cache"))

            val manager = ProjectManager.desktop(home.resolve("projects"))
            manager.registerSharedProjectRoot(shared.toString())

            val listed = manager.list()
            assertEquals(1, listed.size, "only the project-looking folder is listed: $listed")
            val summary = listed.single()
            assertEquals("TermuxApp", summary.name)
            assertEquals(project.toRealPath().toString(), summary.rootPath)
            assertTrue(summary.unrecognized, "it has not been opened yet")
            assertTrue(summary.compatibility, "its Gradle build files are recognized")

            manager.open(summary.rootPath).use { ide ->
                assertEquals("TermuxApp", ide.projectDisplayName())
            }

            assertEquals("fun main() = Unit\n", Files.readString(source), "the existing source is untouched")
            assertTrue(Files.isDirectory(project.resolve(".platform")), "the model is written beside the project")
            assertFalse(
                Files.exists(home.resolve("projects").resolve("TermuxApp")),
                "opening in place must not copy the project into CodeAssist's own storage",
            )
        }
    }
}
