package com.bidaide

import com.bidaide.adb.AdbLocationAction
import com.bidaide.adb.AdbLogcat
import com.bidaide.adb.AdbPrefs
import com.bidaide.adb.AdbShell
import com.bidaide.adb.BugReports
import com.bidaide.adb.DeviceProfile
import com.bidaide.adb.Sqlite
import com.bidaide.gh.GhAccounts
import com.bidaide.gh.GhDiscussions
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhNotifications
import com.bidaide.gh.GhProjects
import com.bidaide.gh.GhReleases
import com.bidaide.gh.GhRepos
import com.bidaide.gh.GhSearch
import com.bidaide.gh.GhTimeline
import com.bidaide.github.runs.CiArtifacts
import com.bidaide.github.ui.SavedReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoreFeaturesTest {

    // --- GitHub ---

    @Test
    fun `timeline keeps comments and reviews with their text and drops noise`() {
        val slurped = """[[
            {"event":"committed","sha":"ce81d1e8f62788ca","message":"fixes\n\nlong text","author":{"name":"David","date":"2026-10-01T10:00:00Z"}},
            {"event":"subscribed","actor":{"login":"a"},"created_at":"2026-10-01T10:01:00Z"},
            {"event":"review_requested","actor":{"login":"david"},"requested_reviewer":{"login":"ana"},"created_at":"2026-10-01T10:02:00Z"},
            {"event":"reviewed","user":{"login":"ana"},"state":"changes_requested","body":"Falta un test","submitted_at":"2026-10-01T11:00:00Z"}
        ],[
            {"event":"commented","actor":{"login":"david"},"user":{"login":"david"},"body":"Hecho","created_at":"2026-10-01T12:00:00Z"},
            {"event":"labeled","actor":{"login":"david"},"label":{"name":"bug"},"created_at":"2026-10-01T12:01:00Z"},
            {"event":"closed","actor":null,"created_at":"2026-10-01T13:00:00Z"}
        ]]"""
        val events = GhTimeline.parse(slurped)
        assertEquals(listOf("committed", "review_requested", "reviewed", "commented", "labeled", "closed"), events.map { it.kind })
        assertEquals("commit ce81d1e fixes", events[0].summary)
        assertEquals("David", events[0].actor)
        assertEquals("pidió review a ana", events[1].summary)
        assertEquals("pidió cambios", events[2].summary)
        assertEquals("Falta un test", events[2].body)
        assertEquals("2026-10-01T11:00:00Z", events[2].date)
        assertEquals("añadió la label bug", events[4].summary)
        assertNull(events[4].body)
        assertEquals("ghost", events[5].actor)
    }

    @Test
    fun `timeline field does not disturb pull request parsing`() {
        val pr = GhJson.pullRequest("""{"number":8,"title":"t","state":"OPEN"}""")
        assertNull(pr.timeline)
        assertEquals(1, pr.copy(timeline = GhTimeline.parse("""[[{"event":"closed","created_at":"x"}]]""")).timeline?.size)
    }

    @Test
    fun `commit detail carries parent and files`() {
        val detail = GhJson.commitDetail(
            """{"sha":"abc","parents":[{"sha":"p1"},{"sha":"p2"}],"files":[{"filename":"a.kt","status":"modified","additions":1,"deletions":2,"patch":"@@ -1 +1 @@"}]}"""
        )
        assertEquals("abc", detail.sha)
        assertEquals("p1", detail.parent)
        assertEquals("a.kt", detail.files.single().filename)
        assertNull(GhJson.commitDetail("""{"sha":"root","parents":[]}""").parent)
        assertTrue(GhJson.commitDetail("""{"sha":"root","parents":[]}""").files.isEmpty())
    }

    @Test
    fun `notifications map api urls to pages`() {
        val json = """[{"id":"26259296556","unread":true,"reason":"review_requested","updated_at":"2026-10-11T14:13:37Z",
            "subject":{"title":"fixes","url":"https://api.github.com/repos/acme/app/pulls/9","latest_comment_url":null,"type":"PullRequest"},
            "repository":{"full_name":"acme/app"}},
            {"id":"2","unread":false,"reason":"ci_activity","subject":{"title":"CI","url":null,"type":"CheckSuite"},"repository":{"full_name":"o/r"}}]"""
        val (pr, ci) = GhNotifications.parseList(json)
        assertEquals(9, pr.number)
        assertEquals("https://github.com/acme/app/pull/9", pr.webUrl)
        assertEquals("te piden review", pr.reasonLabel)
        assertTrue(pr.unread)
        assertNull(ci.number)
        assertEquals("https://github.com/o/r", ci.webUrl)
        assertEquals("https://github.com/o/r/issues/3", GhNotifications.webUrl("https://api.github.com/repos/o/r/issues/3", "o/r"))
        assertEquals("https://github.com/o/r/commit/abc", GhNotifications.webUrl("https://api.github.com/repos/o/r/commits/abc", "o/r"))
        assertEquals("https://github.com/o/r/releases", GhNotifications.webUrl("https://api.github.com/repos/o/r/releases/123", "o/r"))
        assertEquals(listOf("api", "repos/{owner}/{repo}/notifications?per_page=50&all=false"), GhNotifications.list(thisRepo = true, all = false))
        assertEquals(listOf("api", "notifications?per_page=50&all=true"), GhNotifications.list(thisRepo = false, all = true))
    }

    @Test
    fun `release create never leaves gh without notes`() {
        assertEquals(
            listOf("release", "create", "v1", "--title", "Uno", "--generate-notes", "--draft"),
            GhReleases.create("v1", "Uno", "", generateNotes = true, draft = true, prerelease = false, target = ""),
        )
        assertEquals(
            listOf("release", "create", "v1", "--notes", "", "--prerelease", "--target", "main"),
            GhReleases.create("v1", "", "", generateNotes = false, draft = false, prerelease = true, target = " main "),
        )
        assertEquals(
            listOf("release", "create", "v1", "--notes", "hola", "--generate-notes"),
            GhReleases.create("v1", "", "hola", generateNotes = true, draft = false, prerelease = false, target = ""),
        )
        val release = GhReleases.parse("""{"name":"","tagName":"v2","assets":[{"name":"app.apk","size":10,"downloadCount":3}]}""")
        assertEquals("v2", release.title)
        assertEquals("app.apk", release.assets?.single()?.name)
    }

    @Test
    fun `discussions parse list, detail and setup`() {
        val list = """{"data":{"repository":{"discussions":{"nodes":[
            {"id":"D_1","number":4,"title":"Idea","url":"https://github.com/o/r/discussions/4","updatedAt":"2026-01-01T00:00:00Z",
             "author":null,"category":{"name":"Ideas"},"answer":{"id":"C_1"},"comments":{"totalCount":2}}]}}}}"""
        val discussion = GhDiscussions.parseList(list).single()
        assertEquals(4, discussion.number)
        assertEquals("ghost", discussion.author)
        assertTrue(discussion.answered)
        assertEquals(2, discussion.commentCount)

        val detail = """{"data":{"repository":{"discussion":{"id":"D_1","number":4,"title":"Idea","url":"u","author":{"login":"ana"},
            "category":{"name":"Ideas"},"answer":null,"body":"texto","comments":{"totalCount":1,"nodes":[
            {"body":"sí","createdAt":"c","isAnswer":true,"author":{"login":"david"},"replies":{"nodes":[{"body":"gracias","createdAt":"c2","isAnswer":false,"author":{"login":"ana"}}]}}]}}}}}"""
        val parsed = GhDiscussions.parse(detail)
        assertEquals("texto", parsed.body)
        assertFalse(parsed.answered)
        assertTrue(parsed.comments.single().isAnswer)
        assertEquals("gracias", parsed.comments.single().replies.single().body)

        val disabled = GhDiscussions.parseSetup(
            """{"data":{"repository":{"id":"R_test123","hasDiscussionsEnabled":false,"discussions":{"nodes":[]},"discussionCategories":{"nodes":[]}}}}"""
        )
        assertFalse(disabled.enabled)
        assertEquals("R_test123", disabled.repositoryId)
        assertTrue(GhDiscussions.parseList("""{"data":{"repository":{"discussions":{"nodes":[]}}}}""").isEmpty())

        val create = GhDiscussions.create("R_1", "CAT_1", "Título", "Cuerpo")
        assertEquals(
            "query=mutation(\$repositoryId:ID!,\$categoryId:ID!,\$title:String!,\$body:String!)" +
                "{createDiscussion(input:{repositoryId:\$repositoryId,categoryId:\$categoryId,title:\$title,body:\$body}){discussion{url}}}",
            create.last(),
        )
        assertTrue(create.containsAll(listOf("repositoryId=R_1", "categoryId=CAT_1", "title=Título", "body=Cuerpo")))
    }

    @Test
    fun `projects parse boards, cards and the status field`() {
        val projects = GhProjects.parseList(
            """{"projects":[{"closed":false,"id":"PVT_1","items":{"totalCount":7},"number":3,"shortDescription":"","title":"Sprint","url":"u"}],"totalCount":1}""", "acme",
        )
        assertEquals(3, projects.single().number)
        assertEquals(7, projects.single().itemCount)
        assertEquals("acme", projects.single().owner)

        val items = GhProjects.parseItems(
            """{"items":[
                {"id":"PVTI_1","title":"Arreglar login","status":"In Progress","content":{"type":"Issue","number":12,"title":"Arreglar login","url":"u12","repository":"acme/app"}},
                {"id":"PVTI_2","title":"Nota","content":{"type":"DraftIssue","title":"Nota","body":""}}],"totalCount":2}"""
        )
        assertEquals("In Progress", items[0].status)
        assertEquals(12, items[0].number)
        assertEquals("#12 Arreglar login", items[0].toString())
        assertEquals(GhProjects.NO_STATUS, items[1].status)
        assertNull(items[1].number)

        val field = GhProjects.parseStatusField(
            """{"fields":[{"id":"F_0","name":"Title","type":"ProjectV2Field"},
                {"id":"F_1","name":"Priority","type":"ProjectV2SingleSelectField","options":[{"id":"p1","name":"Alta"}]},
                {"id":"F_2","name":"Status","type":"ProjectV2SingleSelectField","options":[{"id":"o1","name":"Todo"},{"id":"o2","name":"Done"}]}]}"""
        )
        assertEquals("F_2", field?.id)
        assertEquals(listOf("Todo", "Done"), field?.options?.map { it.name })
        assertNull(GhProjects.parseStatusField("""{"fields":[{"id":"F_0","name":"Title"}]}"""))

        assertTrue(GhProjects.isScopeError("error: your authentication token is missing required scopes [read:project]"))
        assertFalse(GhProjects.isScopeError("HTTP 404"))
        assertFalse(GhProjects.isScopeError(null))
    }

    @Test
    fun `accounts come from gh auth status`() {
        val json = """{"hosts":{"github.com":[
            {"state":"success","active":true,"host":"github.com","login":"DavidUps","tokenSource":"keyring","scopes":"gist, read:org, repo, workflow","gitProtocol":"https"},
            {"state":"success","active":false,"host":"github.com","login":"work"},
            {"state":"error","active":false,"host":"github.com","login":"caducada"}]}}"""
        val accounts = GhAccounts.parse(json)
        assertEquals(listOf("DavidUps", "work"), accounts.map { it.login })
        assertTrue(accounts[0].active)
        assertEquals(listOf("auth", "switch", "--hostname", "github.com", "--user", "work"), GhAccounts.switch(accounts[1]))
        assertTrue(GhAccounts.parse("""{"hosts":{}}""").isEmpty())
    }

    @Test
    fun `code search, blame and commit lookup`() {
        assertEquals(
            listOf("search", "code", "fun", "main", "--repo", "o/r", "--limit", "50", "--json", "path,repository,url,textMatches"),
            GhSearch.code("  fun   main ", "o/r", null),
        )
        assertEquals(listOf("search", "code", "x", "--owner", "o"), GhSearch.code("x", null, "o").take(5))
        assertFalse("--repo" in GhSearch.code("x", null, null) || "--owner" in GhSearch.code("x", null, null))

        val match = GhSearch.parseCode(
            """[{"path":"app/Main.kt","repository":{"nameWithOwner":"o/r"},"url":"https://github.com/o/r/blob/x/app/Main.kt","textMatches":[{"fragment":"fun main() {"}]}]"""
        ).single()
        assertEquals("o/r", match.repository)
        assertEquals("fun main() {", match.fragment)
        assertTrue(GhSearch.parseCode("[]").isEmpty())

        assertEquals("ce81d1e8f62788ca3514c20b4c267645a488a89b", GhSearch.blamedCommit("ce81d1e8f62788ca3514c20b4c267645a488a89b 10 10 1\nauthor David"))
        assertNull(GhSearch.blamedCommit("0000000000000000000000000000000000000000 10 10 1\nauthor Not Committed Yet"))
        assertNull(GhSearch.blamedCommit(""))

        val pull = GhSearch.parseCommitPulls(
            """[{"number":3,"title":"abierta","html_url":"u3","merged_at":null},{"number":8,"title":"fusionada","html_url":"u8","merged_at":"2026-01-01T00:00:00Z"}]"""
        )
        assertEquals(8, pull?.number)
        assertNull(GhSearch.parseCommitPulls("[]"))
    }

    @Test
    fun `publish builds gh repo create`() {
        assertEquals(
            listOf("repo", "create", "acme/app", "--source", ".", "--remote", "origin", "--private", "--description", "Una app", "--push"),
            GhRepos.publish("acme/app", " Una app ", private = true, remote = "origin", push = true),
        )
        assertEquals(
            listOf("repo", "create", "app", "--source", ".", "--remote", "github", "--public"),
            GhRepos.publish("app", "", private = false, remote = "github", push = false),
        )
    }

    @Test
    fun `expired artifacts are not offered`() {
        val json = """{"total_count":2,"artifacts":[{"name":"app-debug","expired":false},{"name":"viejo","expired":true}]}"""
        assertEquals(listOf("app-debug"), CiArtifacts.parseNames(json))
        assertTrue(CiArtifacts.parseNames("""{"total_count":0,"artifacts":[]}""").isEmpty())
        assertEquals(
            listOf("run", "download", "42", "--name", "app-debug", "--dir", File("/tmp/x").path),
            CiArtifacts.downloadArgs(42, "app-debug", File("/tmp/x")),
        )
    }

    @Test
    fun `saved replies turn escaped newlines into line breaks`() {
        val replies = SavedReplies.parse("# comentario\nNit = nit: esto es opcional\nTests = Falta un test.\\n\\n¿Puedes añadirlo?\nsin igual\n = vacío")
        assertEquals(listOf("Nit", "Tests"), replies.map { it.name })
        assertEquals("Falta un test.\n\n¿Puedes añadirlo?", replies[1].text)
    }

    // --- ADB ---

    @Test
    fun `device profiles parse and validate`() {
        val profiles = DeviceProfile.parse("Accesibilidad = Dark=ON, font=1.3, animations=off\nÁrabe = locale=ar-EG, rotation=landscape\nRoto = volumen=11\nMal = dark=si")
        assertEquals(listOf("Accesibilidad", "Árabe", "Roto", "Mal"), profiles.map { it.name })
        assertEquals(mapOf("dark" to "on", "font" to "1.3", "animations" to "off"), profiles[0].settings)
        assertNull(profiles[0].problem())
        assertFalse(profiles[0].needsPackage)
        assertEquals("ar-EG", profiles[1].settings["locale"])
        assertTrue(profiles[1].needsPackage)
        assertNull(profiles[1].problem())
        assertEquals("unknown key “volumen”", profiles[2].problem())
        assertEquals("invalid value “si” for “dark”", profiles[3].problem())
        assertNotNull(DeviceProfile("x", mapOf("font" to "9")).problem())
        assertNotNull(DeviceProfile("x", emptyMap()).problem())
        assertEquals("system", DeviceProfile.parse("S = locale=System").single().settings["locale"])
    }

    @Test
    fun `text is escaped for input text`() {
        assertEquals("input text 'hola%smundo'", AdbShell.inputTextCommand("hola mundo"))
        assertEquals("input text 'it'\\''s&ok'", AdbShell.inputTextCommand("it's&ok"))
    }

    @Test
    fun `version name comes out of dumpsys`() {
        assertEquals("2.4.1-debug", AdbShell.parseVersionName("    versionCode=241 minSdk=26 targetSdk=35\n    versionName=2.4.1-debug\n    splits=[base]"))
        assertNull(AdbShell.parseVersionName("Unable to find package: x"))
    }

    @Test
    fun `preferences listing ignores errors and odd names`() {
        assertEquals(listOf("a_prefs.xml", "com.app_preferences.xml"), AdbPrefs.parseList("com.app_preferences.xml\na_prefs.xml\nnotes.txt\n"))
        assertTrue(AdbPrefs.parseList("ls: shared_prefs: No such file or directory").isEmpty())
        assertTrue(AdbPrefs.parseList("bad name.xml\n\$(rm).xml").isEmpty())
    }

    @Test
    fun `preferences xml is checked before it is written`() {
        assertNull(AdbPrefs.xmlProblem("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"onboarded\" value=\"true\" />\n</map>"))
        assertNotNull(AdbPrefs.xmlProblem("<map><boolean name=\"x\" value=\"true\"></map>"))
        assertNotNull(AdbPrefs.xmlProblem(""))
    }

    @Test
    fun `gps positions are validated`() {
        assertEquals(40.4168 to -3.7038, AdbLocationAction.parsePosition(" 40.4168, -3.7038 "))
        assertNull(AdbLocationAction.parsePosition("91, 0"))
        assertNull(AdbLocationAction.parsePosition("40.4"))
        assertNull(AdbLocationAction.parsePosition("1, 2, 3"))
        assertNull(AdbLocationAction.parsePosition("madrid"))
    }

    @Test
    fun `logcat is limited to the app process`() {
        assertEquals(
            listOf("/sdk/adb", "-s", "emulator-5554", "logcat", "-v", "threadtime", "-v", "color", "--pid=1234"),
            AdbLogcat.command("/sdk/adb", "emulator-5554", "1234"),
        )
    }

    @Test
    fun `bug report body has environment and a bounded log`() {
        val info = BugReports.Info("Google Pixel 8", "15", "35", "com.app", "1.2.0")
        val body = BugReports.body(info, "linea 1\n```\nlinea 3")
        assertTrue(body.contains("- App: `com.app` 1.2.0"))
        assertTrue(body.contains("- Device: Google Pixel 8"))
        assertTrue(body.contains("- Android: 15 (API 35)"))
        assertTrue(body.contains("<details><summary>Logcat</summary>"))
        // A code fence inside the log must not close ours.
        assertEquals(2, Regex("```").findAll(body).count())
        assertFalse(BugReports.body(info, "  ").contains("Logcat"))
        assertTrue(BugReports.body(info, "x".repeat(200_000)).length < 65_536)
        assertTrue(BugReports.body(BugReports.Info("d", "15", "35", "com.app", null), "").contains("(unknown version)"))
    }

    @Test
    fun `sqlite files can be browsed with the bundled driver`() {
        val file = Files.createTempFile("bidaide-test", ".db").toFile()
        try {
            assertTrue(Sqlite.run(file, "CREATE TABLE \"my \"\"table\" (id INTEGER PRIMARY KEY, name TEXT, data BLOB)").message.contains("rows affected"))
            Sqlite.run(file, "INSERT INTO \"my \"\"table\" (name, data) VALUES ('uno', x'0102'), (NULL, NULL)")
            assertEquals(listOf("my \"table"), Sqlite.tables(file))
            val result = Sqlite.run(file, Sqlite.selectAll("my \"table"))
            assertEquals(listOf("id", "name", "data"), result.columns)
            assertEquals(listOf(listOf("1", "uno", "<blob 2 bytes>"), listOf("2", "NULL", "NULL")), result.rows)
            assertFalse(result.truncated)
            assertEquals("2 rows", result.message)
        } finally {
            file.delete()
        }
    }
}
