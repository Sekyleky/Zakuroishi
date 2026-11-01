#!/usr/bin/env bash
# device-test.sh - drive the Zakuroishi app on an Android handheld over adb
# for performance tests. Never taps the UI (uses keyevent/joystick input only).
#
# Env (defaults):
#   ADB       adb binary                    (adb)
#   ADB_ARGS  extra adb args                (empty), e.g. "-P 5038 -s b225f4aa"
#   GAME      16-hex title id               (needed by get/set/launch/fight/ab)
#   OUT       output dir for logs           (./device-test-out)
#
# Commands:
#   list                      list games known to the app
#   get                       print current settings JSON
#   set KEY=VALUE [...]       change settings (true/false/numbers unquoted, else quoted)
#   launch                    force-stop, clear logcat, launch game, wait <=120 s for "benchmark"
#   title                     press A, wait, press A again (run after launch to reach the field)
#   walk-to-battle            walk left/right (<=30 times) until "code for Battle" is logged
#   fight NAME                launch + title + walk-to-battle, save $OUT/NAME.log, summarize it
#   summary FILE              summarize a saved log
#   ab NAME KEY=VALUE [...]   fight NAME-on (current settings) and NAME-off (KEY=VALUE applied),
#                             restore the old values, print both summaries
#   wake                      wake the screen (never unlocks)
#
# Examples:
#   export GAME=0123456789abcdef
#   ./device-test.sh list
#   ./device-test.sh get
#   ./device-test.sh set fpsCap=60 hd=true
#   ADB_ARGS="-P 5038 -s b225f4aa" ./device-test.sh fight baseline
#   ./device-test.sh summary ./device-test-out/baseline.log
#   ./device-test.sh ab nohd hd=false

export MSYS_NO_PATHCONV=1   # stop Git Bash from mangling /-prefixed args
set -u

ADB=${ADB:-adb}
ADB_ARGS=${ADB_ARGS:-}
GAME=${GAME:-}
OUT=${OUT:-./device-test-out}
PKG=io.github.zakuro.android
LOG_FILTER='Zakuro:*'
RESTORE=()

die() { echo "error: $*" >&2; exit 1; }
usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }
adb_cmd() { "$ADB" $ADB_ARGS "$@"; }   # ADB_ARGS is intentionally word-split
need_game() { [[ $GAME =~ ^[0-9A-Fa-f]{16}$ ]] || die "set GAME to a 16-hex title id (got '$GAME')"; }

# settings_call list | get | set JSON -> prints result data; returns 1 if result != 0
settings_call() {
  local remote="am broadcast -n $PKG/.DebugSettings" out code data
  if [[ $1 == list ]]; then
    remote+=" --ez list true"
  else
    remote+=" --es game $GAME"
    [[ $1 == set ]] && remote+=" --es set '$2'"   # single quotes: the device shell re-parses
  fi
  out=$(adb_cmd shell "$remote" 2>&1)
  code=$(sed -n 's/.*Broadcast completed: result=\([-0-9]*\).*/\1/p' <<<"$out" | tail -1)
  data=$(sed -n 's/.*Broadcast completed: .*data="\(.*\)".*/\1/p' <<<"$out" | tail -1)
  if [[ $code != 0 ]]; then
    echo "$out" >&2
    echo "error: settings broadcast failed (result=${code:-none})" >&2
    return 1
  fi
  printf '%s\n' "$data"
}

# json_from KEY=VALUE... -> {"KEY":VALUE,...}
json_from() {
  local kv k v j="" sep="" num_re='^-?[0-9]+(\.[0-9]+)?$'
  for kv in "$@"; do
    [[ $kv == *=* ]] || die "expected KEY=VALUE, got '$kv'"
    k=${kv%%=*}; v=${kv#*=}
    [[ $k =~ ^[A-Za-z0-9_.]+$ ]] || die "bad key '$k'"
    if [[ $v != true && $v != false && ! $v =~ $num_re ]]; then
      case $v in *\'*|*\"*|*\\*) die "value of $k may not contain quotes or backslashes" ;; esac
      v="\"$v\""
    fi
    j+="$sep\"$k\":$v"; sep=,
  done
  printf '{%s}\n' "$j"
}

# json_get JSON KEY -> raw value (string quotes stripped); empty if absent
json_get() {
  printf '%s' "$1" | grep -o "\"$2\":[^,}]*" | head -1 | sed -E 's/^"[^"]*"://; s/^"(.*)"$/\1/'
}

# kv KEY LINE -> value of KEY=... in LINE, or ?
kv() {
  local v
  v=$(grep -o "$1=[^ ,;]*" <<<"$2" | head -1 | cut -d= -f2-)
  echo "${v:-?}"
}

set_values() {   # set_values KEY=VALUE... (prints returned data)
  local json
  json=$(json_from "$@") || return 1
  settings_call set "$json"
}

cmd_set() {
  [[ $# -gt 0 ]] || die "usage: set KEY=VALUE [...]"
  need_game
  set_values "$@" || exit 1
}

do_launch() {
  need_game
  if adb_cmd shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
    echo "device is locked — unlock it"
    exit 3
  fi
  adb_cmd logcat -c
  adb_cmd shell am force-stop "$PKG"
  out=$(adb_cmd shell "am start -W -a android.intent.action.VIEW -d zakuro://game/$GAME")
  grep -q Error <<<"$out" && die "launch failed: $out"
  echo "waiting for 'benchmark' in logcat (<=120 s)..."
  local end=$((SECONDS + 120))
  while (( SECONDS < end )); do
    if adb_cmd logcat -d -s "$LOG_FILTER" | grep -q benchmark; then
      echo "benchmark seen"
      return 0
    fi
    sleep 2
  done
  die "timed out waiting for 'benchmark' in logcat"
}

do_title() {
  sleep 3
  adb_cmd shell input keyevent BUTTON_A
  sleep 4
  adb_cmd shell input keyevent BUTTON_A
  sleep 6
}

do_walk() {
  local i
  for i in $(seq 1 30); do
    adb_cmd shell 'for i in $(seq 1 25); do input joystick motionevent MOVE -1 0; done; for i in $(seq 1 25); do input joystick motionevent MOVE 1 0; done'
    if adb_cmd logcat -d -s "$LOG_FILTER" | grep -q 'code for Battle'; then
      adb_cmd shell input joystick motionevent MOVE 0 0
      sleep 20
      echo "battle reached after $i walk(s)"
      return 0
    fi
  done
  adb_cmd shell input joystick motionevent MOVE 0 0
  sleep 20
  return 1
}

run_fight() {   # run_fight NAME: launch, title, walk, save $OUT/NAME.log
  local name=${1:-}
  [[ $name =~ ^[A-Za-z0-9._-]+$ ]] || die "usage: fight NAME (letters, digits, . _ -)"
  need_game
  mkdir -p "$OUT" || die "cannot create $OUT"
  do_launch
  do_title
  do_walk || echo "warning: no 'code for Battle' after 30 walks" >&2
  adb_cmd logcat -d -s "$LOG_FILTER" > "$OUT/$name.log"
  echo "saved $OUT/$name.log"
}

cmd_fight() {
  run_fight "${1:-}"
  cmd_summary "$OUT/$1.log"
}

cmd_summary() {
  local f=${1:-} log loads hitches cnt over top bench
  [[ -f $f ]] || die "usage: summary FILE (no such file: '$f')"
  log=$(tr -d '\r' < "$f")

  loads=$(grep -E 'code for .*checked in [0-9.]+ ms' <<<"$log" \
    | sed -E 's/.*code for ([^ ,]+).*checked in ([0-9.]+) ms.*/code for \1, checked in \2 ms/')

  hitches=$(grep 'frame hitch' <<<"$log" | while IFS= read -r line; do
    echo "core_ms=$(kv core_ms "$line") present_ms=$(kv present_ms "$line") pipelines_compiled=$(kv pipelines_compiled "$line") programs_compiled=$(kv programs_compiled "$line")"
  done)
  cnt=$(grep -c . <<<"$hitches")
  over=$(awk '{for(i=1;i<=NF;i++) if($i ~ /^present_ms=/){split($i,a,"="); if(a[2]+0>25) c++}} END{print c+0}' <<<"$hitches")
  top=$(sed -E 's/.*core_ms=([^ ]*).*/\1/' <<<"$hitches" | sort -rn | head -5 | tr '\n' ' ')

  bench=$(grep 'benchmark' <<<"$log" | tail -6 | while IFS= read -r line; do
    echo "fps=$(kv fps "$line") worst_core_ms=$(kv worst_core_ms "$line")"
  done)

  echo "== code loads"
  printf '%s\n' "${loads:-(none)}"
  echo "== frame hitches"
  printf '%s\n' "${hitches:-(none)}"
  echo "hitch lines: $cnt, with present_ms>25: $over"
  echo "top 5 core_ms: ${top:-(none)}"
  echo "== benchmark (last 6)"
  printf '%s\n' "${bench:-(none)}"
}

restore_now() {   # EXIT trap for ab: put back the values captured before the test
  (( ${#RESTORE[@]} )) || return 0
  echo "restoring: ${RESTORE[*]}"
  settings_call set "$(json_from "${RESTORE[@]}")" >/dev/null \
    || echo "warning: restore failed, settings may be left changed" >&2
  RESTORE=()
}

cmd_ab() {
  local name=${1:-} kv_arg k old before
  [[ $name =~ ^[A-Za-z0-9._-]+$ && $# -gt 1 ]] || die "usage: ab NAME KEY=VALUE [...]"
  shift
  need_game
  json_from "$@" >/dev/null || exit 1
  before=$(settings_call get) || exit 1
  trap restore_now EXIT
  for kv_arg in "$@"; do
    k=${kv_arg%%=*}
    old=$(json_get "$before" "$k")
    if [[ -n $old ]]; then
      RESTORE+=("$k=$old")
    else
      echo "warning: '$k' not in current settings, not restoring it" >&2
    fi
  done
  run_fight "$name-on"
  set_values "$@" >/dev/null || exit 1
  run_fight "$name-off"
  restore_now
  echo "===== $name-on (current settings)"
  cmd_summary "$OUT/$name-on.log"
  echo "===== $name-off ($*)"
  cmd_summary "$OUT/$name-off.log"
}

cmd=${1:-}
case $cmd in
  ''|-h|--help|help) usage; exit 0 ;;
esac
shift
command -v "$ADB" >/dev/null 2>&1 || die "adb not found: $ADB (set ADB=path)"

case $cmd in
  list)           settings_call list ;;
  get)            need_game; settings_call get ;;
  set)            cmd_set "$@" ;;
  launch)         do_launch ;;
  title)          do_title ;;
  walk-to-battle) do_walk ;;
  fight)          cmd_fight "$@" ;;
  summary)        cmd_summary "$@" ;;
  ab)             cmd_ab "$@" ;;
  wake)           adb_cmd shell input keyevent KEYCODE_WAKEUP ;;
  *)              usage; exit 2 ;;
esac
