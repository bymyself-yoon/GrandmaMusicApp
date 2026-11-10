package com.suyuni.grandmamusic.sync

import org.json.JSONObject

/**
 * PC의 "토큰QR만들기.html"이 만든 QR 내용을 읽어.
 * 형식: {"v":1,"repo":"아이디/저장소","token":"github_pat_..."}
 * (토큰만 들어 있는 QR도 받아줘. 그땐 저장소는 지금 설정 그대로)
 */
object TokenQr {
    data class Payload(val repo: String?, val token: String)

    private val TOKEN = Regex("^(github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})$")
    private val REPO = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")

    fun parse(raw: String?): Payload? {
        val text = raw?.trim() ?: return null
        if (TOKEN.matches(text)) return Payload(null, text)
        return try {
            val o = JSONObject(text)
            val token = o.optString("token").trim()
            val repo = o.optString("repo").trim().trim('/').ifEmpty { null }
            if (!TOKEN.matches(token)) return null
            if (repo != null && !REPO.matches(repo)) return null
            Payload(repo, token)
        } catch (e: Exception) {
            null
        }
    }
}
