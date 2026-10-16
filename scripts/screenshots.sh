#!/usr/bin/env bash
set -euo pipefail

# Reproducible Android screenshots of branchDAM Mobile, paired with a
# throwaway branchDAM demo instance.
#
#   make screenshots                      # writes docs/screenshots/
#   make screenshots OUT=/abs/path/shots  # any other directory
#   scripts/screenshots.sh --out DIR
#
# What it does, end to end:
#   1. Builds the gomobile AAR and the debug APK from this checkout.
#   2. Boots a throwaway copy of the AVD (default: pixel_10) headless. Only
#      its config.ini is copied into a scratch ANDROID_AVD_HOME, so the
#      emulator starts from a fresh userdata image and the user's AVD is
#      never written to. SELinux is permissive in that copy, see
#      start_emulator for why.
#   3. Starts the branchDAM demo (`make demo`'s scripts/demo/instance.mjs)
#      from the sibling checkout: synthetic media, scratch config/DB,
#      127.0.0.1:3975. The emulator reaches it as 10.0.2.2:3975.
#   4. Creates a companion pairing on the demo (POST
#      /api/v1/companion/pairings, X-Forwarded-Host: 10.0.2.2:3975) and
#      hands its server/key/agent to the app through the plain prefs file
#      that the app's own PrefKeyMigration moves into
#      EncryptedSharedPreferences on first start (debug APK: run-as).
#   5. Pushes a few of the demo's sample photos into /sdcard/DCIM/Camera
#      (camera JPEGs plus graded "_edited" copies, so the lineage audit
#      queue has candidates) and rescans media.
#   6. Dark theme, demo-mode status bar (10:00, full battery, wifi),
#      animations off; then captures the audit queue, gallery and sync
#      status screens with `adb exec-out screencap -p`.
#   7. On any exit: kills the emulator and the demo, removes scratch dirs.
#
# Environment (all optional):
#   ANDROID_HOME    Android SDK (default: ~/Android/Sdk). adb and emulator
#                   are used from here, not from PATH.
#   AVD             source AVD name (default: pixel_10)
#   EMU_PORT        emulator console port, even (default: 5580); the
#                   device is always addressed as emulator-$EMU_PORT
#   BRANCHDAM_DIR   branchDAM checkout with scripts/demo (default:
#                   ../branchDAM next to this repo; needs branchdam#530)
#   DEMO_PORT       demo instance port (default: 3975)
#   DEBUG_DIR       on failure, save a screencap, UI dump, logcat and the
#                   build/demo logs here before teardown
#
# Prerequisites: JDK 17+, an Android NDK, Go, node + npm, ffmpeg, exiftool, curl, jq, KVM.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OUT="docs/screenshots"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
        --out=*) OUT="${1#--out=}"; shift ;;
        -h|--help) awk 'NR > 3 && /^#/ { sub(/^# ?/, ""); print; next } NR > 3 { exit }' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
# A relative OUT is relative to the caller's directory (make passes the
# repo root, since the Makefile target runs from there).
mkdir -p "${OUT}"
OUT="$(cd "${OUT}" && pwd)"

SDK="${ANDROID_HOME:-${HOME}/Android/Sdk}"
ADB="${SDK}/platform-tools/adb"
EMULATOR="${SDK}/emulator/emulator"
AVD="${AVD:-pixel_10}"
EMU_PORT="${EMU_PORT:-5580}"
SERIAL="emulator-${EMU_PORT}"
BRANCHDAM_DIR="${BRANCHDAM_DIR:-${REPO_ROOT}/../branchDAM}"
DEMO_PORT="${DEMO_PORT:-3975}"
DEMO_URL="http://127.0.0.1:${DEMO_PORT}"
PKG="com.branchdam.mobile"
APK="${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
SHOT_AVD="branchdam-shots"

log() { printf '[screenshots] %s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

# --- preflight ---------------------------------------------------------

[[ -x "${ADB}" ]] || die "adb not found at ${ADB} (set ANDROID_HOME)"
[[ -x "${EMULATOR}" ]] || die "emulator not found at ${EMULATOR} (set ANDROID_HOME)"
for tool in node npm go ffmpeg exiftool curl jq java; do
    command -v "${tool}" >/dev/null 2>&1 || die "${tool} not found in PATH"
done
[[ -f "${BRANCHDAM_DIR}/scripts/demo/instance.mjs" ]] ||
    die "${BRANCHDAM_DIR}/scripts/demo/instance.mjs missing; set BRANCHDAM_DIR to a branchDAM checkout that has the demo tooling (branchdam#530)"
[[ "$((EMU_PORT % 2))" -eq 0 && "${EMU_PORT}" -ge 5554 && "${EMU_PORT}" -le 5682 ]] ||
    die "EMU_PORT must be an even number from 5554 to 5682"
[[ -r /dev/kvm && -w /dev/kvm ]] || die "/dev/kvm is not accessible; the emulator needs KVM"

AVD_SRC_HOME="${ANDROID_AVD_HOME:-${HOME}/.android/avd}"
[[ -f "${AVD_SRC_HOME}/${AVD}.ini" ]] || die "AVD ${AVD} not found in ${AVD_SRC_HOME}"
AVD_SRC_DIR="$(sed -n 's/^path=//p' "${AVD_SRC_HOME}/${AVD}.ini")"
[[ -f "${AVD_SRC_DIR}/config.ini" ]] || die "${AVD_SRC_DIR}/config.ini not found"
AVD_TARGET="$(sed -n 's/^target=//p' "${AVD_SRC_HOME}/${AVD}.ini")"

if "${ADB}" devices | awk 'NR > 1 {print $1}' | grep -qx "${SERIAL}"; then
    die "${SERIAL} is already attached; pick another EMU_PORT"
fi
for port in "${EMU_PORT}" "$((EMU_PORT + 1))"; do
    if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
        die "port ${port} is in use; pick another EMU_PORT"
    fi
done

SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/branchdam-mobile-shots.XXXXXX")"
EMU_PID=""
DEMO_PID=""
DEMO_DIR=""

# --- teardown ----------------------------------------------------------

# Waits up to $2 seconds for pid $1 to exit.
wait_gone() {
    local i
    for ((i = 0; i < $2 * 2; i++)); do
        kill -0 "$1" 2>/dev/null || return 0
        sleep 0.5
    done
    return 1
}

cleanup() {
    local rc=$?
    set +e
    trap - EXIT INT TERM
    if [[ "${rc}" -ne 0 && -n "${DEBUG_DIR:-}" && -n "${EMU_PID}" ]]; then
        mkdir -p "${DEBUG_DIR}"
        "${ADB}" -s "${SERIAL}" exec-out screencap -p >"${DEBUG_DIR}/failure.png" 2>/dev/null
        "${ADB}" -s "${SERIAL}" exec-out uiautomator dump /dev/tty >"${DEBUG_DIR}/failure.xml" 2>/dev/null
        "${ADB}" -s "${SERIAL}" logcat -d >"${DEBUG_DIR}/logcat.txt" 2>/dev/null
        cp "${SCRATCH}"/*.log "${DEBUG_DIR}/" 2>/dev/null
        log "failure diagnostics in ${DEBUG_DIR}"
    fi
    if [[ -n "${EMU_PID}" ]]; then
        log "stopping the emulator"
        "${ADB}" -s "${SERIAL}" emu kill >/dev/null 2>&1
        if ! wait_gone "${EMU_PID}" 20; then
            kill -TERM -- "-${EMU_PID}" 2>/dev/null
            wait_gone "${EMU_PID}" 10 || kill -KILL -- "-${EMU_PID}" 2>/dev/null
        fi
    fi
    if [[ -n "${DEMO_PID}" ]]; then
        log "stopping the demo instance"
        # The demo runs in its own process group (setsid): this reaches node
        # and the branchdam server it spawned, even mid-startup.
        kill -TERM -- "-${DEMO_PID}" 2>/dev/null
        wait_gone "${DEMO_PID}" 20 || kill -KILL -- "-${DEMO_PID}" 2>/dev/null
        # node deletes its scratch dir on SIGTERM once it is up; before
        # that (or after SIGKILL) the dir it announced is ours to remove.
        if [[ -n "${DEMO_DIR}" && -d "${DEMO_DIR}" ]]; then
            rm -rf -- "${DEMO_DIR}"
        fi
    fi
    rm -rf -- "${SCRATCH}"
    exit "${rc}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- emulator ----------------------------------------------------------

start_emulator() {
    local avd_home="${SCRATCH}/avd"
    mkdir -p "${avd_home}/${SHOT_AVD}.avd"
    # Hardware profile only: identity, data-partition path and snapshot
    # settings are dropped so the copy cold-boots from a fresh userdata.
    grep -vE '^(avd\.id|avd\.name|disk\.dataPartition\.path|firstboot\.|fastboot\.)' \
        "${AVD_SRC_DIR}/config.ini" >"${avd_home}/${SHOT_AVD}.avd/config.ini"
    printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=%s\n' \
        "${avd_home}/${SHOT_AVD}.avd" "${AVD_TARGET}" >"${avd_home}/${SHOT_AVD}.ini"

    log "booting a throwaway copy of ${AVD} as ${SERIAL}"
    # -selinux permissive: zygote then skips the per-app seccomp filter.
    # The x86_64 build of the Go core is killed by that filter (SIGSYS:
    # modernc.org/libc issues a raw lstat(2) on linux/amd64, which the app
    # seccomp policy does not allow), and the arm64 build dies
    # under the image's ARM translation, so this is the only way to run the
    # app on an x86_64 emulator. It applies to this throwaway AVD only.
    ANDROID_AVD_HOME="${avd_home}" ANDROID_SDK_ROOT="${SDK}" setsid "${EMULATOR}" \
        -avd "${SHOT_AVD}" -port "${EMU_PORT}" \
        -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -memory 4096 -selinux permissive -gpu swiftshader_indirect -camera-back none -camera-front none \
        >"${SCRATCH}/emulator.log" 2>&1 &
    EMU_PID=$!
}

wait_for_boot() {
    local i booted=""
    for ((i = 0; i < 180; i++)); do
        kill -0 "${EMU_PID}" 2>/dev/null || {
            tail -n 20 "${SCRATCH}/emulator.log" >&2
            die "the emulator exited during boot"
        }
        booted="$("${ADB}" -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || true
        [[ "${booted}" == "1" ]] && break
        sleep 2
    done
    [[ "${booted}" == "1" ]] || die "the emulator did not finish booting in 6 minutes"
    # Belt and braces against a stray emulator on the same port: the
    # console must report our throwaway AVD before anything is installed.
    local name
    name="$("${ADB}" -s "${SERIAL}" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')"
    [[ "${name}" == "${SHOT_AVD}" ]] || die "${SERIAL} is '${name}', not ${SHOT_AVD}; refusing to touch it"
    for ((i = 0; i < 60; i++)); do
        "${ADB}" -s "${SERIAL}" shell pm path android >/dev/null 2>&1 && break
        sleep 1
    done
    log "${SERIAL} booted"
}

# --- demo instance -----------------------------------------------------

start_demo() {
    log "installing branchDAM web deps (if needed)"
    make -C "${BRANCHDAM_DIR}" --no-print-directory web/node_modules >"${SCRATCH}/demo-deps.log" 2>&1 || {
        tail -n 20 "${SCRATCH}/demo-deps.log" >&2
        die "npm ci in ${BRANCHDAM_DIR}/web failed"
    }
    log "starting the branchDAM demo on ${DEMO_URL}"
    (cd "${BRANCHDAM_DIR}" && DEMO_PORT="${DEMO_PORT}" exec setsid node scripts/demo/instance.mjs) \
        >"${SCRATCH}/demo.log" 2>&1 &
    DEMO_PID=$!
}

wait_for_demo() {
    local i
    for ((i = 0; i < 1200; i++)); do
        if [[ -z "${DEMO_DIR}" ]]; then
            local announced tmp="${TMPDIR:-/tmp}"
            announced="$(sed -n 's/^\[demo\] scratch dir //p' "${SCRATCH}/demo.log" | head -n 1)"
            # Teardown rm -rf's this path, so accept only what instance.mjs
            # creates: <os.tmpdir()>/branchdam-demo.
            if [[ -n "${announced}" ]]; then
                [[ "${announced}" == "${tmp%/}/branchdam-demo" ]] ||
                    die "unexpected demo scratch dir '${announced}'"
                DEMO_DIR="${announced}"
            fi
        fi
        grep -q 'branchDAM demo is up' "${SCRATCH}/demo.log" && {
            log "demo is up (${DEMO_DIR})"
            return 0
        }
        kill -0 "${DEMO_PID}" 2>/dev/null || {
            tail -n 20 "${SCRATCH}/demo.log" >&2
            die "the demo instance exited during startup"
        }
        sleep 1
    done
    die "the demo instance was not ready after 20 minutes"
}

# --- app ---------------------------------------------------------------

build_apk() {
    export ANDROID_HOME="${SDK}"
    # Always rebuild the Go core: a branchdam.aar left over from another
    # checkout would silently put stale code in the screenshots.
    if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
        ANDROID_NDK_HOME="$(find "${SDK}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
        [[ -n "${ANDROID_NDK_HOME}" ]] || die "no NDK under ${SDK}/ndk (needed to build branchdam.aar)"
        export ANDROID_NDK_HOME
    fi
    log "building branchdam.aar"
    "${REPO_ROOT}/scripts/build-mobile.sh" --android-only >"${SCRATCH}/aar.log" 2>&1 || {
        tail -n 30 "${SCRATCH}/aar.log" >&2
        die "AAR build failed"
    }
    log "building the debug APK"
    make -C "${REPO_ROOT}" --no-print-directory android >"${SCRATCH}/gradle.log" 2>&1 || {
        tail -n 30 "${SCRATCH}/gradle.log" >&2
        die "gradle assembleDebug failed"
    }
    [[ -f "${APK}" ]] || die "no APK at ${APK}"
}

adb_s() { "${ADB}" -s "${SERIAL}" "$@"; }

prepare_device() {
    # The UI is driven by its (English) labels. A fresh userdata image
    # boots in the image's default locale; make sure that is en-US.
    local locale
    locale="$(adb_s shell getprop persist.sys.locale | tr -d '\r')"
    [[ -n "${locale}" ]] || locale="$(adb_s shell getprop ro.product.locale | tr -d '\r')"
    [[ "${locale}" == en-US ]] || die "device locale is '${locale}', expected en-US"
    log "dark theme, demo-mode status bar, animations off"
    adb_s shell cmd uimode night yes >/dev/null
    local s
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb_s shell settings put global "${s}" 0
    done
    adb_s shell settings put global sysui_demo_allowed 1
    demo_cmd() { adb_s shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
    demo_cmd enter
    demo_cmd clock -e hhmm 1000
    demo_cmd battery -e level 100 -e plugged false -e powersave false
    demo_cmd network -e wifi show -e level 4 -e fully true
    demo_cmd network -e mobile hide
    demo_cmd network -e airplane hide
    demo_cmd notifications -e visible false
}

push_media() {
    local media="${SCRATCH}/media" n=0 shoot slug stem
    mkdir -p "${media}"
    # Camera JPEGs plus their graded exports, renamed the way an in-phone
    # editor saves them (<stem>_edited.jpg), which EditCorrelator turns
    # into 0.95-confidence audit candidates. All synthetic demo media; the
    # shoots are discovered (archive/<year>/<slug>/), not hardcoded.
    for shoot in "${DEMO_DIR}"/archive/*/*/; do
        slug="$(basename "${shoot}")"
        while read -r stem; do
            cp "${shoot}${stem}.JPG" "${media}/${stem}.JPG"
            n=$((n + 1))
            if [[ -f "${DEMO_DIR}/exports/${slug}/${stem}_edit.jpg" ]]; then
                cp "${DEMO_DIR}/exports/${slug}/${stem}_edit.jpg" "${media}/${stem}_edited.jpg"
                n=$((n + 1))
            fi
        done < <(find "${shoot}" -maxdepth 1 -name '*.JPG' -printf '%f\n' | sed 's/\.JPG$//' | sort | head -n 3)
    done
    [[ "${n}" -gt 0 ]] || die "no sample media found under ${DEMO_DIR}"
    # MediaStore ignores an EXIF capture time without a UTC offset (DATE_TAKEN
    # stays NULL) and the app only lists media with DATE_TAKEN set, so tag
    # the copies the way a phone camera would.
    exiftool -q -overwrite_original -EXIF:OffsetTimeOriginal=+00:00 "${media}"
    local i
    for ((i = 0; i < 60; i++)); do
        adb_s shell test -d /sdcard/DCIM 2>/dev/null && break
        sleep 2
    done
    log "pushing ${n} sample photos to /sdcard/DCIM/Camera"
    adb_s shell mkdir -p /sdcard/DCIM/Camera
    adb_s push "${media}/." /sdcard/DCIM/Camera/ >/dev/null 2>&1
    adb_s shell content call --uri content://media/external/file \
        --method scan_volume --arg external_primary >/dev/null
    local count=0
    for ((i = 0; i < 30; i++)); do
        count="$(adb_s shell content query --uri content://media/external/images/media \
            --projection _display_name:datetaken 2>/dev/null | grep -c 'datetaken=[0-9]' || true)"
        [[ "${count}" -ge "${n}" ]] && break
        sleep 1
    done
    log "MediaStore lists ${count} of ${n} sample photos with a capture time"
    [[ "${count}" -ge "${n}" ]] || die "MediaStore did not index all sample photos"
}

xml_escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'; }

pair_app() {
    log "creating a companion pairing on the demo"
    local resp
    resp="$(curl -fsS -X POST "${DEMO_URL}/api/v1/companion/pairings" \
        -H 'Content-Type: application/json' \
        -H 'X-Authentik-Username: demo' \
        -H 'X-Authentik-Email: demo@example.com' \
        -H 'X-Authentik-Groups: branchdam-admins' \
        -H "X-Forwarded-Host: 10.0.2.2:${DEMO_PORT}" \
        -d '{"friendlyLabel":"Pixel 10 (screenshots)"}')"
    local server key agent
    # pairingUrl may be empty (the demo has no secrets box); the QR payload
    # format is branchdam://?server=…&key=…&agent=…, built from these.
    server="http://10.0.2.2:${DEMO_PORT}"
    key="$(jq -r '.apiKey' <<<"${resp}")"
    agent="$(jq -r '.agentId' <<<"${resp}")"
    [[ -n "${key}" && "${key}" != null && -n "${agent}" && "${agent}" != null ]] ||
        die "unexpected pairing response"

    log "installing the APK"
    adb_s install -r -g "${APK}" >/dev/null

    # Same keys the QR flow persists. The app's PrefKeyMigration moves them
    # from the plain prefs file into EncryptedSharedPreferences on start.
    local prefs="${SCRATCH}/branchdam_prefs.xml"
    {
        printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        printf '    <string name="server_url">%s</string>\n' "$(xml_escape <<<"${server}")"
        printf '    <string name="api_key">%s</string>\n' "$(xml_escape <<<"${key}")" # pragma: allowlist secret
        printf '    <string name="agent_id">%s</string>\n' "$(xml_escape <<<"${agent}")"
        printf '    <boolean name="branchdam_onboarding_completed" value="true" />\n'
        printf '</map>\n'
    } >"${prefs}"
    adb_s shell "run-as ${PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/branchdam_prefs.xml'" <"${prefs}"
    adb_s shell "run-as ${PKG} cat shared_prefs/branchdam_prefs.xml" | grep -q 'agent_id' ||
        die "could not write the app's prefs via run-as"
}

# Prints the current UI hierarchy. A system "isn't responding" dialog (a
# slow, loaded host can make System UI or GMS miss an ANR deadline right
# after boot) is answered with "Wait" and an empty dump is printed, so
# callers simply poll again.
ui_dump() {
    local xml bounds
    xml="$(adb_s exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
    if grep -q "isn't responding" <<<"${xml}"; then
        bounds="$(bounds_of "Wait" <<<"${xml}")"
        if [[ -n "${bounds}" ]]; then
            log "dismissing a system 'isn't responding' dialog"
            tap_bounds "${bounds}"
        fi
        return 0
    fi
    printf '%s\n' "${xml}"
}

# Reads a UI dump on stdin; prints "x1 y1 x2 y2" of the first node whose
# text is exactly $1.
bounds_of() {
    grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" |
        head -n 1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true
}

tap_bounds() {
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$1"
    adb_s shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
}

# Taps the first on-screen node whose text is exactly $1.
tap_text() {
    local i bounds
    for ((i = 0; i < 20; i++)); do
        bounds="$(ui_dump | bounds_of "$1")"
        if [[ -n "${bounds}" ]]; then
            tap_bounds "${bounds}"
            return 0
        fi
        sleep 1
    done
    log "no on-screen element with text '$1'"
    return 1
}

# Waits up to $2 polls (default 60) until the screen shows text matching
# the regex $1.
wait_text() {
    local i
    for ((i = 0; i < ${2:-60}; i++)); do
        ui_dump | grep -Eq "$1" && return 0
        sleep 1
    done
    return 1
}

# Waits until the device has gone 5 polls in a row without a system
# "isn't responding" dialog (post-boot work settles), up to 5 minutes.
settle() {
    local i calm=0 xml
    log "waiting for the system to settle"
    for ((i = 0; i < 150 && calm < 5; i++)); do
        xml="$(ui_dump)"
        if [[ -n "${xml}" ]]; then calm=$((calm + 1)); else calm=0; fi
        sleep 2
    done
}

# Captures to a temp file first, so a failed screencap never truncates a
# committed PNG.
capture() {
    local tmp="${SCRATCH}/$1.png"
    sleep 2
    adb_s exec-out screencap -p >"${tmp}" || die "screencap failed for $1"
    # Bytes 2-4 of the PNG signature spell "PNG".
    [[ "$(head -c 4 "${tmp}" | tail -c 3)" == PNG ]] ||
        die "screencap for $1 is not a PNG"
    mv -f "${tmp}" "${OUT}/$1.png"
    log "captured $1.png"
}

capture_screens() {
    log "launching the app"
    adb_s shell am start -W -n "${PKG}/.MainActivity" >/dev/null
    wait_text 'text="Lineage Audit"' 120 || die "the app did not open on the Lineage Audit screen"
    # The audit queue loads from MediaStore on open; give it a moment.
    wait_text '_edited\.jpg' 60 || die "no audit candidates on screen"
    capture audit-queue

    tap_text "Gallery" || die "cannot open the Gallery tab"
    wait_text 'class="android.widget.Image"|DSC0' 60 || die "the gallery shows no photos"
    capture gallery

    tap_text "Sync" || die "cannot open the Sync tab"
    wait_text 'text="Sync Status"' 30 || die "the Sync tab did not open"
    tap_text "Sync Now" || die "no Sync Now button"
    wait_text 'All backed up' 300 || die "uploads still pending after 5 minutes"
    # Re-read the status so "Last Sync" reflects the finished run.
    tap_text "Refresh Status" || true
    capture sync-status
}

# --- main --------------------------------------------------------------

# The demo build and its media rendering are CPU-heavy; booting the
# emulator only once they are done keeps first boot from starving into
# system ANRs on a busy host.
start_demo
build_apk
wait_for_demo
start_emulator
wait_for_boot
settle
prepare_device
push_media
pair_app
capture_screens
log "screenshots in ${OUT}"
