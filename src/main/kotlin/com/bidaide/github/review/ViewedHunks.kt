package com.bidaide.github.review

/**
 * Which individual changes of each file the reviewer has ticked off. GitHub only knows "file viewed", so these marks
 * are local; the two are kept in step: ticking the last change of a file marks the file as viewed, and marking the
 * file ticks all its changes.
 *
 * A change is identified by where it starts on both sides (see [key]), which survives switching between the
 * side-by-side and unified viewers.
 */
class ViewedHunks {
    private val marked = HashMap<String, MutableSet<String>>()

    /** How many changes the diff viewer found in a file, known once the file has been opened. */
    private val totals = HashMap<String, Int>()

    fun isViewed(path: String, key: String, fileViewed: Boolean): Boolean = fileViewed || key in marked[path].orEmpty()

    /**
     * Ticks or unticks one change. [allKeys] are all the changes of the file as currently shown. Returns the new
     * "viewed" state the file should take, or null when the file's state does not change.
     */
    fun set(path: String, key: String, viewed: Boolean, allKeys: Collection<String>, fileViewed: Boolean): Boolean? {
        totals[path] = allKeys.size
        if (viewed) {
            val keys = marked.getOrPut(path) { HashSet() }
            keys += key
            return if (!fileViewed && keys.containsAll(allKeys)) true else null
        }
        if (fileViewed) {
            // The file was viewed as a whole: everything but this change stays ticked.
            marked[path] = allKeys.filterTo(HashSet()) { it != key }
            return false
        }
        marked[path]?.remove(key)
        return null
    }

    /** The file was marked or unmarked as a whole: unmarking it unticks every change. */
    fun fileSet(path: String, viewed: Boolean) {
        if (!viewed) marked.remove(path)
    }

    fun seen(path: String, allKeys: Collection<String>) {
        totals[path] = allKeys.size
    }

    /** "3/8" while a file is partly reviewed, null when nothing is ticked or the file was never opened. */
    fun progress(path: String, fileViewed: Boolean): String? {
        val total = totals[path] ?: return null
        val done = marked[path]?.size ?: 0
        return if (fileViewed || done == 0 || total == 0) null else "${minOf(done, total)}/$total"
    }

    // --- Kept between IDE sessions, per pull request and head commit: after new commits the changes are other changes. ---

    fun serialize(headSha: String): String =
        (listOf(headSha) + marked.filterValues { it.isNotEmpty() }.map { (path, keys) -> path + FIELD + keys.sorted().joinToString(ITEM) }).joinToString(LINE)

    fun restore(text: String?, headSha: String) {
        val lines = text?.split(LINE).orEmpty()
        if (lines.firstOrNull() != headSha) return
        for (line in lines.drop(1)) {
            val path = line.substringBefore(FIELD, "")
            val keys = line.substringAfter(FIELD, "").split(ITEM).filter { it.isNotEmpty() }
            if (path.isNotEmpty() && keys.isNotEmpty()) marked[path] = keys.toHashSet()
        }
    }

    companion object {
        private const val LINE = "\n"
        private const val FIELD = "\t"
        private const val ITEM = ","

        /** [baseStart] and [headStart] are the 0-based lines where the change begins in each version of the file. */
        fun key(baseStart: Int, headStart: Int): String = "$baseStart:$headStart"

        /** A file shown whole (added or deleted) is one single change. */
        const val WHOLE_FILE = "file"
    }
}
