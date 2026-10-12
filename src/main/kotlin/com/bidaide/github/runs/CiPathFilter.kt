package com.bidaide.github.runs

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Links file paths found in CI logs to the local sources. CI paths are absolute on the runner
 * (`/home/runner/work/repo/repo/app/src/Foo.kt:12:5`), so the longest path suffix that exists under [root] wins.
 */
class CiPathFilter(private val project: Project, private val root: File) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = PATH.findAll(line).mapNotNull { match ->
            val local = resolve(root, match.groupValues[1]) ?: return@mapNotNull null
            val file = LocalFileSystem.getInstance().findFileByIoFile(local) ?: return@mapNotNull null
            val lineNumber = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
            val column = match.groupValues[3].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
            Filter.ResultItem(lineStart + match.range.first, lineStart + match.range.last + 1, OpenFileHyperlinkInfo(project, file, lineNumber, column))
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    companion object {
        /** `dir/dir/file.ext`, optionally followed by `:line` and `:column`. */
        val PATH = Regex("""((?:[\w.@+-]+/)+[\w.@+-]+\.[A-Za-z]{1,8})(?::(\d+))?(?::(\d+))?""")

        /** The existing file under [root] matching the longest trailing part of [path], if any. */
        fun resolve(root: File, path: String): File? {
            val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
            for (start in segments.indices) {
                if (segments.size - start < 2) break
                val candidate = File(root, segments.subList(start, segments.size).joinToString("/"))
                if (candidate.isFile) return candidate
            }
            return null
        }
    }
}
