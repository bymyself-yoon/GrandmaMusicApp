package com.suyuni.grandmamusic.update

// 업데이트 관련 저장값과, 설치 결과를 받는 리시버들

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.suyuni.grandmamusic.widget.WidgetUpdater

class UpdatePrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0L)
        set(v) = sp.edit().putLong("lastCheck", v).apply()

    var status: String
        get() = sp.getString("status", "아직 확인 안 함") ?: ""
        set(v) = sp.edit().putString("status", v).apply()
}

/** PackageInstaller가 설치 결과를 알려주는 곳 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = UpdatePrefs(context)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    prefs.status = "설치 확인을 기다리는 중 (알림을 눌러주세요)"
                    Updater.askUser(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.status = "업데이트 완료"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 이유"
                prefs.status = "설치 실패: $msg"
            }
        }
    }
}

/** 업데이트가 끝나고 새 버전이 깔리면 위젯을 새로 그려 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdatePrefs(context).status = "업데이트 완료"
            WidgetUpdater.updateAll(context)
        }
    }
}
