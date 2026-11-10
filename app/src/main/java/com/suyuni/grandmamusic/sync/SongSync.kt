package com.suyuni.grandmamusic.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import com.suyuni.grandmamusic.data.Palette
import com.suyuni.grandmamusic.data.Playlist
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.data.Song
import com.suyuni.grandmamusic.widget.WidgetUpdater
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 노래 저장소 주소·토큰·마지막 결과 */
class SyncPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("song_sync", Context.MODE_PRIVATE)

    /** "아이디/저장소이름" 형식 */
    var repo: String
        get() = sp.getString("repo", DEFAULT_REPO) ?: DEFAULT_REPO
        set(v) = sp.edit().putString("repo", v.trim().trim('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    var status: String
        get() = sp.getString("status", "아직 받은 적 없음") ?: ""
        set(v) = sp.edit().putString("status", v).apply()

    companion object {
        const val DEFAULT_REPO = "bymyself-yoon/GrandmaSongs"
    }
}

/**
 * 비공개 GitHub 저장소(폴더 = 재생목록)에서 새 노래를 받아와.
 * - 새 노래·바뀐 노래만 받아 (이미 받은 건 다시 안 받음)
 * - 저장소에서 지운 노래는 폰에서도 지워 (폰에서 직접 넣은 노래는 그대로)
 * - 100MB 넘는 파일(Git LFS)도 받아
 * - 받은 파일이 깨지지 않았는지 확인해 (sha1 / sha256)
 */
object SongSync {
    sealed class Result {
        data class Done(val added: Int, val updated: Int, val removed: Int, val failed: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /** 백그라운드 스레드에서 불러. [progress]로 "받는 중 3/10: 제목" 같은 진행 상황을 알려줘. */
    fun run(ctx: Context, progress: (String) -> Unit): Result {
        if (!running.compareAndSet(false, true)) return Result.Failed("이미 받는 중이에요")
        val prefs = SyncPrefs(ctx)
        try {
            val result = doRun(ctx.applicationContext, prefs, progress)
            prefs.status = when (result) {
                is Result.Done -> buildString {
                    if (result.added + result.updated + result.removed == 0) append("바뀐 노래 없음")
                    else {
                        val parts = mutableListOf<String>()
                        if (result.added > 0) parts += "새 노래 ${result.added}곡"
                        if (result.updated > 0) parts += "바뀐 노래 ${result.updated}곡"
                        if (result.removed > 0) parts += "지운 노래 ${result.removed}곡"
                        append(parts.joinToString(", "))
                    }
                    if (result.failed > 0) append(" · ${result.failed}곡 실패 (다시 누르면 이어서 받아요)")
                }
                is Result.Failed -> result.message
            }
            if (result is Result.Done) prefs.lastSync = System.currentTimeMillis()
            return result
        } finally {
            running.set(false)
        }
    }

    private fun doRun(ctx: Context, prefs: SyncPrefs, progress: (String) -> Unit): Result {
        val repo = prefs.repo
        val token = prefs.token
        if (!Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(repo)) {
            return Result.Failed("저장소 이름을 '아이디/저장소이름' 형식으로 넣어주세요")
        }
        if (token.isEmpty()) return Result.Failed("먼저 QR 코드로 토큰을 넣어주세요")
        val gh = GitHub(repo, token)

        progress("노래 목록 확인 중…")
        val branch: String
        val remote: List<RemoteSong>
        try {
            branch = JSONObject(gh.getText("https://api.github.com/repos/$repo")).getString("default_branch")
            val tree = JSONObject(gh.getText("https://api.github.com/repos/$repo/git/trees/${enc(branch)}?recursive=1"))
            val arr = tree.getJSONArray("tree")
            val entries = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("type") == "blob" }
                .map { RemoteSong(it.getString("path"), it.getString("sha"), it.optLong("size", 0L)) }
            remote = SyncPlanner.filterSongs(entries)
        } catch (e: HttpError) {
            return Result.Failed(
                when (e.code) {
                    401 -> "토큰이 틀렸거나 만료됐어요"
                    403 -> "토큰 권한이 부족하거나 GitHub 사용 제한에 걸렸어요 (잠시 후 다시)"
                    404 -> "저장소를 찾을 수 없어요 (저장소 이름과 토큰 권한 확인)"
                    409 -> "저장소가 비어 있어요. 노래를 먼저 올려주세요"
                    else -> "노래 목록을 못 가져왔어요 (HTTP ${e.code})"
                },
            )
        } catch (e: IOException) {
            return Result.Failed("인터넷 연결을 확인해주세요")
        } catch (e: Exception) {
            return Result.Failed("노래 목록을 읽지 못했어요: ${e.javaClass.simpleName}")
        }

        val plan = SyncPlanner.plan(remote, Repository.load(ctx))

        // 1) 지울 노래부터 지워 (저장 공간 확보)
        var removed = 0
        if (plan.removals.isNotEmpty() || plan.goneFolders.isNotEmpty()) {
            val rm = plan.removals.groupBy({ it.first }, { it.second })
            val list = Repository.load(ctx).mapNotNull { p ->
                val drop = rm[p.id].orEmpty().toSet()
                val songs = p.songs.filterNot { it.id in drop }
                removed += p.songs.size - songs.size
                when {
                    p.id in plan.goneFolders && songs.isEmpty() -> null // 폴더가 없어지고 비었으면 재생목록도 지움
                    p.id in plan.goneFolders -> p.copy(songs = songs, remoteFolder = null) // 폰에서 넣은 노래가 남아 있으면 남김
                    else -> p.copy(songs = songs)
                }
            }
            Repository.save(ctx, list)
            Repository.cleanupOrphans(ctx)
            WidgetUpdater.updateAll(ctx)
        }

        // 2) 새 노래·바뀐 노래 받기
        val existingPaths = Repository.load(ctx).flatMap { p -> p.songs.mapNotNull { it.remotePath } }.toSet()
        var added = 0
        var updated = 0
        var failed = 0
        plan.downloads.forEachIndexed { i, rs ->
            val label = "받는 중 ${i + 1}/${plan.downloads.size}: ${rs.title}"
            progress(label)
            try {
                val song = download(ctx, gh, branch, rs) { pct -> progress("$label ($pct%)") }
                applySong(ctx, rs, song)
                if (rs.path in existingPaths) updated++ else added++
                WidgetUpdater.updateAll(ctx)
            } catch (e: NoSpaceError) {
                failed += plan.downloads.size - i
                Repository.cleanupOrphans(ctx)
                return Result.Failed("저장 공간이 부족해요 (${added}곡 받음). 폰 공간을 비우고 다시 눌러주세요")
            } catch (e: HttpError) {
                if (e.code == 401) return Result.Failed("토큰이 틀렸거나 만료됐어요")
                failed++
            } catch (e: IOException) {
                failed++
                // 인터넷이 끊겼으면 더 시도하지 않고 멈춰
                if (e !is VerifyError) {
                    Repository.cleanupOrphans(ctx)
                    return Result.Failed("인터넷이 끊겼어요 (${added + updated}곡 받음). 다시 누르면 이어서 받아요")
                }
            }
        }
        Repository.cleanupOrphans(ctx)
        WidgetUpdater.updateAll(ctx)
        return Result.Done(added, updated, removed, failed)
    }

    /** 받은 노래를 재생목록에 넣어. 폴더 이름과 같은 재생목록이 없으면 새로 만들어. */
    @Synchronized
    private fun applySong(ctx: Context, rs: RemoteSong, song: Song) {
        val list = Repository.load(ctx).toMutableList()
        var idx = list.indexOfFirst { it.remoteFolder == rs.folder }
        if (idx < 0) idx = list.indexOfFirst { it.remoteFolder == null && it.name == rs.folder }
        if (idx < 0) {
            val usedColors = list.map { it.colorIndex }.toSet()
            list.add(
                Playlist(
                    name = rs.folder,
                    colorIndex = Palette.entries.indices.firstOrNull { it !in usedColors } ?: (list.size % Palette.entries.size),
                    showOnWidget = list.count { it.showOnWidget } < Repository.WIDGET_MAX,
                    remoteFolder = rs.folder,
                ),
            )
            idx = list.lastIndex
        }
        val p = list[idx]
        val songs = p.songs.toMutableList()
        val old = songs.indexOfFirst { it.remotePath == rs.path }
        // 파일이 바뀐 노래는 같은 자리에서 바꾸고, 새 노래는 맨 뒤에 붙여 (수유니가 정한 순서 유지)
        if (old >= 0) songs[old] = song.copy(title = songs[old].title.takeIf { it != titleOf(songs[old]) } ?: song.title)
        else songs.add(song)
        list[idx] = p.copy(songs = songs, remoteFolder = rs.folder)
        Repository.save(ctx, list)
    }

    /** 편집 화면에서 제목을 직접 고친 적이 없으면 저장소 파일 이름을 따라가게 */
    private fun titleOf(s: Song): String = s.remotePath?.let { RemoteSong(it, "", 0).title } ?: s.title

    private fun download(ctx: Context, gh: GitHub, branch: String, rs: RemoteSong, onPct: (Int) -> Unit): Song {
        val ext = rs.path.substringAfterLast('.', "mp3").lowercase().take(5)
        val target = File(Repository.musicDir(ctx), "${UUID.randomUUID()}.$ext")
        val tmp = File(ctx.cacheDir, "sync_${UUID.randomUUID()}.part")
        try {
            // 먼저 git 파일(blob)을 받아. 100MB 넘는 파일이면 LFS "안내 쪽지"가 와
            ensureSpace(ctx, rs.size)
            gh.download("https://api.github.com/repos/${gh.repo}/git/blobs/${rs.sha}", tmp, "application/vnd.github.raw", rs.size, onPct)
            val lfs = if (tmp.length() < 1024) parseLfsPointer(tmp.readText()) else null
            if (lfs != null) {
                ensureSpace(ctx, lfs.second)
                val url = "https://media.githubusercontent.com/media/${gh.repo}/${enc(branch)}/" +
                    rs.path.split('/').joinToString("/") { enc(it) }
                gh.download(url, tmp, null, lfs.second, onPct)
                if (tmp.length() != lfs.second || hash(tmp, "SHA-256", null) != lfs.first) {
                    throw VerifyError("LFS 파일 확인 실패")
                }
            } else if (gitBlobSha(tmp) != rs.sha) {
                throw VerifyError("파일 확인 실패")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            throw e
        }
        var duration = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(target.absolutePath)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        return Song(
            fileName = target.name,
            originalName = rs.fileName,
            title = rs.title,
            durationMs = duration,
            remotePath = rs.path,
            remoteSha = rs.sha,
        )
    }

    private fun ensureSpace(ctx: Context, bytes: Long) {
        if (ctx.filesDir.usableSpace < bytes + 50L * 1024 * 1024) throw NoSpaceError()
    }

    /** Git LFS 안내 쪽지에서 (sha256, 크기)를 꺼내. 쪽지가 아니면 null */
    fun parseLfsPointer(text: String): Pair<String, Long>? {
        if (!text.startsWith("version https://git-lfs.github.com/spec/")) return null
        val oid = Regex("(?m)^oid sha256:([0-9a-f]{64})$").find(text)?.groupValues?.get(1) ?: return null
        val size = Regex("(?m)^size (\\d+)$").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return oid to size
    }

    /** git이 파일마다 붙이는 이름(sha1)과 똑같이 계산해서, 받은 파일이 맞는지 확인 */
    fun gitBlobSha(f: File): String = hash(f, "SHA-1", "blob ${f.length()}\u0000".toByteArray())

    private fun hash(f: File, algo: String, prefix: ByteArray?): String {
        val md = MessageDigest.getInstance(algo)
        if (prefix != null) md.update(prefix)
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    class HttpError(val code: Int) : IOException("HTTP $code")
    class NoSpaceError : IOException("no space")
    class VerifyError(msg: String) : IOException(msg)

    /** GitHub에 토큰을 붙여 요청해. 토큰은 GitHub 주소에만 보내고, 다른 곳(파일 저장 서버)으로 넘어가면 안 붙여. */
    class GitHub(val repo: String, private val token: String) {
        private val trusted = setOf("api.github.com", "github.com", "media.githubusercontent.com", "raw.githubusercontent.com")

        fun getText(url: String): String {
            val out = java.io.ByteArrayOutputStream()
            open(url, "application/vnd.github+json") { it.copyTo(out) }
            return out.toString("UTF-8")
        }

        fun download(url: String, dest: File, accept: String?, expectedSize: Long, onPct: (Int) -> Unit) {
            open(url, accept) { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    var lastPct = -1
                    var lastTime = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (expectedSize > 5_000_000) {
                            val pct = (total * 100 / expectedSize).toInt().coerceIn(0, 100)
                            val now = System.currentTimeMillis()
                            if (pct != lastPct && now - lastTime > 700) {
                                lastPct = pct; lastTime = now; onPct(pct)
                            }
                        }
                    }
                }
            }
        }

        private fun open(start: String, accept: String?, body: (InputStream) -> Unit) {
            var url = URL(start)
            repeat(6) {
                val c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "GrandmaMusic-SongSync")
                if (accept != null) c.setRequestProperty("Accept", accept)
                if (url.host in trusted) {
                    c.setRequestProperty("Authorization", "Bearer $token")
                    c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: throw HttpError(code)
                        url = URL(url, loc)
                        return@repeat
                    }
                    if (code !in 200..299) throw HttpError(code)
                    c.inputStream.use(body)
                    return
                } finally {
                    c.disconnect()
                }
            }
            throw IOException("redirect가 너무 많아요")
        }
    }
}
