package com.suyuni.grandmamusic.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.suyuni.grandmamusic.BuildConfig
import com.suyuni.grandmamusic.R
import com.suyuni.grandmamusic.playback.PlaybackService
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 가족 설정의 "업데이트" 버튼을 누르면 최신 버전을 받아서 설치해.
 *
 * 1. [BuildConfig.UPDATE_URL]의 update.json을 확인
 * 2. versionCode가 더 크면 APK를 받아서 (sha256, 패키지 이름, 버전 확인)
 * 3. PackageInstaller로 설치 (노래가 나오고 있었으면 먼저 꺼)
 *    - 안드로이드 12 이상: 두 번째 업데이트부터는 확인 버튼 없이 설치될 수 있어
 *    - 확인이 필요하면 설치 확인창을 바로 띄워
 */
object Updater {
    private const val CHANNEL = "app_update"
    private const val NOTIF_ID = 2001

    sealed class Result {
        data object UpToDate : Result()
        data object NotConfigured : Result()
        data class Installing(val versionName: String) : Result()
        data class Failed(val message: String) : Result()
    }

    val isConfigured: Boolean get() = !BuildConfig.UPDATE_URL.contains("OWNER/REPO")

    /** 확인·다운로드·설치. 백그라운드 스레드에서 불러야 해. */
    fun run(ctx: Context): Result {
        val prefs = UpdatePrefs(ctx)
        if (!isConfigured) return Result.NotConfigured
        prefs.lastCheck = System.currentTimeMillis()
        return try {
            val base = URL(BuildConfig.UPDATE_URL)
            val json = JSONObject(httpGet(base).decodeToString())
            val remoteCode = json.getInt("versionCode")
            val remoteName = json.optString("versionName", remoteCode.toString())
            if (remoteCode <= BuildConfig.VERSION_CODE) {
                prefs.status = "최신 버전이에요 (${BuildConfig.VERSION_NAME})"
                return Result.UpToDate
            }

            val apk = File(ctx.cacheDir, "update.apk")
            val apkUrl = URL(base, json.getString("apkUrl"))
            apk.writeBytes(httpGet(apkUrl))

            val expected = json.optString("sha256", "").lowercase()
            if (expected.isNotEmpty() && sha256(apk) != expected) {
                apk.delete()
                return fail(prefs, "받은 파일이 손상됐어요 (sha256 불일치)")
            }
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: return fail(prefs, "APK 파일을 읽을 수 없어요")
            if (info.packageName != ctx.packageName) return fail(prefs, "다른 앱의 APK예요")
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            if (code <= BuildConfig.VERSION_CODE) return fail(prefs, "APK 버전이 더 높지 않아요 (versionCode 확인)")

            // 설치하면 앱이 다시 시작되니까, 노래가 나오고 있으면 먼저 꺼
            stopPlaybackOnMainThread()
            install(ctx, apk)
            prefs.status = "새 버전 $remoteName 설치 중…"
            Result.Installing(remoteName)
        } catch (e: Exception) {
            fail(prefs, "확인 실패: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    private fun stopPlaybackOnMainThread() {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                PlaybackService.instance?.let { svc ->
                    svc.pausePlayback()
                    svc.stopSelf()
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun fail(prefs: UpdatePrefs, msg: String): Result {
        prefs.status = msg
        return Result.Failed(msg)
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // 이 앱이 직접 설치한 적이 있으면, 확인 버튼 없이 업데이트돼
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val cb = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(cb.intentSender)
        }
    }

    /** 설치에 확인이 필요할 때: 화면이 켜져 있으면 바로 띄우고, 아니면 알림으로 */
    fun askUser(ctx: Context, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AppVisibility.inForeground) {
            try {
                ctx.startActivity(confirm); return
            } catch (_: Exception) {
            }
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "앱 업데이트", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(
            ctx, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("손녀의 선물 새 버전")
            .setContentText("눌러서 업데이트하세요")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 가족 설정에서 직접 확인해야 해
        }
    }

    fun canInstallPackages(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installerOfRecord(ctx: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun httpGet(url: URL): ByteArray {
        var current = url
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "GrandmaMusic-Updater")
            c.setRequestProperty("Cache-Control", "no-cache")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    current = URL(current, c.getHeaderField("Location") ?: error("redirect without location"))
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        error("redirect가 너무 많아요")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 앱 화면이 보이는 중인지 (설치 확인창을 바로 띄울지 알림으로 보낼지 정할 때 씀) */
object AppVisibility {
    @Volatile
    var inForeground = false
}
