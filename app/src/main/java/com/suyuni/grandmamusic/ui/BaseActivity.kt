package com.suyuni.grandmamusic.ui

import androidx.appcompat.app.AppCompatActivity
import com.suyuni.grandmamusic.update.AppVisibility

/** 앱 화면이 보이는 중인지 기록해 (업데이트 확인창을 바로 띄울 수 있게) */
open class BaseActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        AppVisibility.inForeground = true
    }

    override fun onPause() {
        AppVisibility.inForeground = false
        super.onPause()
    }
}
