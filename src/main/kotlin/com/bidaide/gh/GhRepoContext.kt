package com.bidaide.gh

import com.bidaide.BidaideBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File

/** Resolves which git repository of the project `gh` should run in. */
object GhRepoContext {

    fun repository(project: Project, file: VirtualFile? = null): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        file?.let { manager.getRepositoryForFileQuick(it) }?.let { return it }
        val repositories = manager.repositories
        return repositories.firstOrNull { repo -> repo.remotes.any { remote -> remote.urls.any { "github" in it } } }
            ?: repositories.firstOrNull()
    }

    /** `owner/name` of every GitHub remote of the repository, in remote order. */
    fun gitHubRepos(project: Project): List<String> =
        repository(project)?.remotes.orEmpty().flatMap { it.urls }.mapNotNull(::parseGitHubRepo).distinct()

    internal fun parseGitHubRepo(url: String): String? =
        GITHUB_URL.find(url.trim())?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }

    private val GITHUB_URL = Regex("""github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?/?$""")

    fun workDir(project: Project, file: VirtualFile? = null): File? =
        repository(project, file)?.root?.let(VfsUtilCore::virtualToIoFile)

    fun requireWorkDir(project: Project, file: VirtualFile? = null): File =
        workDir(project, file) ?: throw GhException(BidaideBundle.message("gh.repo.noGit"))
}
