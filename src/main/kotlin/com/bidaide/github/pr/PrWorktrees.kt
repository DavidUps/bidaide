package com.bidaide.github.pr

import com.bidaide.gh.GhRepoContext
import java.io.File

/** How "Probar en dispositivo" gets the pull request's code. */
enum class PrTestMode {
    /** Builds in a separate git worktree; the user's checkout, branch and uncommitted changes stay as they are. */
    WORKTREE,

    /** `gh pr checkout` in the user's own copy, then the selected run configuration. */
    CHECKOUT;

    fun label(hasLocalChanges: Boolean): String = when (this) {
        WORKTREE -> "En un worktree aparte (no toca tu copia)"
        CHECKOUT -> "Checkout en tu copia" + if (hasLocalChanges) " — ⚠ tienes cambios sin commit" else ""
    }

    companion object {
        /** Unknown or missing values fall back to the safe mode. */
        fun parse(value: String?): PrTestMode = entries.firstOrNull { it.name == value } ?: WORKTREE
    }
}

/** An application module as Gradle sees it: `:app`, its selected build variant and the id it installs as. */
data class PrAppModule(val gradlePath: String, val variant: String, val applicationId: String?)

/**
 * Pure logic of building a pull request in its own worktree: where the worktrees live, the git and Gradle command
 * lines, and parsing their output. Nothing here touches the IDE, so it is unit tested directly.
 */
object PrWorktrees {
    /** Every worktree Bidaide creates lives under this folder of the IDE system directory, and nothing else is ever deleted. */
    const val FOLDER = "bidaide-worktrees"

    /** Used when the Android model is not available (project not synced): the usual single-app layout. */
    val FALLBACK_MODULE = PrAppModule(":app", "debug", null)

    fun root(systemDir: File): File = File(systemDir, FOLDER)

    /**
     * One folder per local repository: its directory name plus a hash of its path, so two clones of the same
     * repository never share (and fight over) a worktree.
     */
    fun repoFolderName(repoRoot: String): String {
        val path = repoRoot.trimEnd('/', '\\')
        val name = path.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE_CHARS, "-").ifEmpty { "repo" }
        return "$name-%08x".format(path.hashCode())
    }

    fun repoDir(systemDir: File, repoRoot: String): File = File(root(systemDir), repoFolderName(repoRoot))

    fun worktreeDir(systemDir: File, repoRoot: String, number: Int): File = File(repoDir(systemDir, repoRoot), "pr-$number")

    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

    /**
     * The remote whose URL points at the repository `gh` works with ([nameWithOwner]), so the fetch reaches the
     * repository the pull request belongs to even when there are forks among the remotes. Null when none does.
     */
    fun remoteFor(remotes: Map<String, List<String>>, nameWithOwner: String): String? =
        remotes.entries.firstOrNull { (_, urls) ->
            urls.any { GhRepoContext.parseGitHubRepo(it).equals(nameWithOwner, ignoreCase = true) }
        }?.key

    /** Fallback when no remote matches: fetching straight from the URL still works for public repositories and HTTPS credentials. */
    fun repoUrl(nameWithOwner: String): String = "https://github.com/$nameWithOwner.git"

    /** GitHub keeps every pull request's head here, forks included; fetching it moves no local branch. */
    fun fetchArgs(remote: String, number: Int): List<String> = listOf("--no-tags", remote, "pull/$number/head")

    fun addArgs(dir: File, commit: String): List<String> = listOf("add", "--detach", dir.path, commit)

    /** Our own worktree: whatever the last build left in tracked files can be thrown away. */
    fun updateArgs(commit: String): List<String> = listOf("--detach", "--force", commit)

    fun removeArgs(dir: File): List<String> = listOf("remove", "--force", dir.path)

    /** Paths of the worktrees in `git worktree list --porcelain`, the main checkout first. */
    fun parseWorktreeList(porcelain: String): List<String> =
        porcelain.lineSequence().filter { it.startsWith("worktree ") }.map { it.removePrefix("worktree ").trim() }.toList()

    /** True only for [file] strictly inside [root]; guards every deletion. */
    fun isInside(root: File, file: File): Boolean {
        val base = root.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        return target != base && target.startsWith(base)
    }

    /** The worktrees under [root] among those git knows of; the rest are the user's and never touched. */
    fun ownWorktrees(root: File, paths: List<String>): List<File> = paths.map(::File).filter { isInside(root, it) }

    /** `:app` + `freeDebug` → `:app:installFreeDebug`; the root project has no path prefix. */
    fun installTask(module: PrAppModule): String {
        val task = "install" + module.variant.replaceFirstChar { it.uppercase() }
        return if (module.gradlePath == ":" || module.gradlePath.isEmpty()) ":$task" else "${module.gradlePath}:$task"
    }

    fun wrapperName(windows: Boolean): String = if (windows) "gradlew.bat" else "gradlew"

    fun buildCommand(wrapper: File, task: String): List<String> = listOf(wrapper.path, task, "--console=plain")

    /** Where the Gradle build sits inside the git repository: "" when at its root, null when outside it. */
    fun gradleRootInRepo(repoRoot: File, gradleRoot: File): String? {
        val repo = repoRoot.canonicalFile.toPath()
        val gradle = gradleRoot.canonicalFile.toPath()
        return if (gradle.startsWith(repo)) repo.relativize(gradle).toString() else null
    }

    /** What to tell the user when the build in the worktree fails. */
    fun failureHint(copiedLocalProperties: Boolean): String = buildString {
        append("El worktree solo tiene los archivos versionados en git")
        append(if (copiedLocalProperties) " y una copia de tu local.properties" else "")
        append(". Si la build necesita archivos que no están en el repositorio (google-services.json, ")
        append("claves en .properties...), cópialos a mano al worktree o usa «Checkout en tu copia».")
    }
}
