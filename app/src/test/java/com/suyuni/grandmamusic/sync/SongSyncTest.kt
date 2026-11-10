package com.suyuni.grandmamusic.sync

import com.suyuni.grandmamusic.data.Playlist
import com.suyuni.grandmamusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongSyncTest {
    private fun rs(path: String, sha: String = "s_$path") = RemoteSong(path, sha, 10)
    private fun song(path: String?, sha: String? = path?.let { "s_$it" }, id: String = "id_$path") =
        Song(id = id, fileName = "f", originalName = "o", title = "t", remotePath = path, remoteSha = sha)

    @Test fun filterKeepsOnlyFolderAudioFiles() {
        val got = SyncPlanner.filterSongs(
            listOf(
                rs("README.md"), rs("song.mp3"), rs("찬송가/나 같은 죄인.mp3"), rs("찬송가/cover.jpg"),
                rs("트로트/깊은/폴더.mp3"), rs(".github/x.mp3"), rs("찬송가/.hidden.mp3"), rs("트로트/모음 1시간.M4A"),
            ),
        ).map { it.path }
        assertEquals(listOf("찬송가/나 같은 죄인.mp3", "트로트/모음 1시간.M4A"), got)
    }

    @Test fun titleStripsExtensionAndOrderNumber() {
        assertEquals("나 같은 죄인", rs("찬송가/01 나 같은 죄인.mp3").title)
        assertEquals("동백 아가씨", rs("트로트/02_동백 아가씨.mp3").title)
        assertEquals("2024 히트곡", rs("트로트/2024 히트곡.mp3").title)
        assertEquals("123", rs("트로트/123.mp3").title)
    }

    @Test fun planDownloadsNewAndChangedRemovesDeletedKeepsLocal() {
        val local = listOf(
            Playlist(id = "p1", name = "찬송가", colorIndex = 1, remoteFolder = "찬송가", songs = listOf(
                song("찬송가/a.mp3"),                      // 그대로
                song("찬송가/b.mp3", sha = "old"),         // 바뀜
                song("찬송가/c.mp3"),                      // 저장소에서 지워짐
                song(null, id = "local1"),                 // 폰에서 직접 넣은 노래
            )),
            Playlist(id = "p2", name = "옛날", colorIndex = 2, remoteFolder = "옛날", songs = listOf(song("옛날/x.mp3"))),
        )
        val remote = listOf(rs("찬송가/a.mp3"), rs("찬송가/b.mp3"), rs("트로트/new.mp3"))
        val plan = SyncPlanner.plan(remote, local)
        assertEquals(listOf("찬송가/b.mp3", "트로트/new.mp3"), plan.downloads.map { it.path })
        assertEquals(setOf("p1" to "id_찬송가/c.mp3", "p2" to "id_옛날/x.mp3"), plan.removals.toSet())
        assertEquals(setOf("p2"), plan.goneFolders)
    }

    @Test fun gitBlobShaMatchesGit() {
        val f = File.createTempFile("blob", ".bin")
        f.writeBytes(ByteArray(300_000) { (it * 31 % 251).toByte() })
        val git = ProcessBuilder("git", "hash-object", f.absolutePath).start().inputStream.bufferedReader().readText().trim()
        assertEquals(git, SongSync.gitBlobSha(f))
        f.delete()
    }

    @Test fun parsesLfsPointer() {
        val p = "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 157286400\n"
        assertEquals("a".repeat(64) to 157286400L, SongSync.parseLfsPointer(p))
        assertNull(SongSync.parseLfsPointer("ID3 not a pointer"))
        assertTrue(SongSync.parseLfsPointer(p) != null)
    }
}

class TokenQrTest {
    private val tok = "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab"

    @Test fun parsesJsonPayload() {
        val p = TokenQr.parse("""{"v":1,"repo":"bymyself-yoon/GrandmaSongs","token":"$tok"}""")
        assertEquals(TokenQr.Payload("bymyself-yoon/GrandmaSongs", tok), p)
    }

    @Test fun parsesPlainToken() {
        assertEquals(TokenQr.Payload(null, tok), TokenQr.parse("  $tok \n"))
    }

    @Test fun rejectsGarbage() {
        assertNull(TokenQr.parse("https://example.com"))
        assertNull(TokenQr.parse("""{"repo":"a/b","token":"password123"}"""))
        assertNull(TokenQr.parse("""{"repo":"bad repo","token":"$tok"}"""))
        assertNull(TokenQr.parse(null))
    }
}
