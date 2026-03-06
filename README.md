# BOOKVILLAGE 모형 - Android WebView

React 웹앱을 WebView로 감싸 모바일 앱처럼 실행합니다.

## 요구사항
- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17

## 실행 방법

1. React 프론트엔드 개발 서버 실행 (frontend 폴더):
   ```
   npm run dev
   ```

2. **에뮬레이터** 사용 시: `webAppUrl`이 `http://10.0.2.2:3000` (localhost)이면 그대로 실행

3. **실기기** 사용 시: `MainActivity.kt`의 `webAppUrl`을 PC의 실제 IP로 변경
   - 예: `http://192.168.0.10:3000`

4. Android Studio에서 프로젝트 열고 Run

## 배포

프로덕션 빌드된 React 앱 URL로 `webAppUrl` 변경 후 APK 빌드
