package com.bookvillage.mock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "BookVillage"
        private const val CHANNEL_ID = "bookvillage_channel"
        private const val CHANNEL_NAME = "BookVillage 알림"
    }

    companion object {
        private const val BACKEND =
            "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com"
    }

    // 새 FCM 토큰 발급 시 호출 (앱 설치/재설치, 토큰 갱신)
    // 취약점: 토큰을 서버 인증 없이 로그에 노출 + 백엔드에 무인증 등록
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM Token: $token")

        // SharedPreferences에도 저장
        getSharedPreferences("bookvillage_prefs", Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()

        // 백엔드에 토큰 등록 (인증 없이 /admin/api/notifications/register-token 호출)
        thread { registerTokenToBackend(token) }
    }

    // 푸시 알림 수신 시 호출
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "BookVillage"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: ""
        val url = remoteMessage.data["url"]

        Log.d(TAG, "Notification - title: $title, body: $body, url: $url")

        showNotification(title, body, url)
    }

    private fun registerTokenToBackend(token: String) {
        try {
            val payload = JSONObject().apply {
                put("token", token)
                put("deviceId", android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ))
            }.toString()

            val conn = URL("$BACKEND/admin/api/notifications/register-token")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            val code = conn.responseCode
            Log.d(TAG, "FCM token registered to backend: HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "FCM token backend 등록 실패: ${e.message}")
        }
    }

    private fun showNotification(title: String, body: String, url: String?) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0+ 채널 생성
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        // 알림 클릭 시 MainActivity로 이동 (url 있으면 딥링크로 전달)
        val intent = if (!url.isNullOrEmpty()) {
            Intent(this, MainActivity::class.java).apply {
                putExtra("fcm_url", url)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
