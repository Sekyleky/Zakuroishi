#!/usr/bin/env bash
# device-test.sh - drive the Zakuroishi app on an Android handheld over adb
# for performance tests. Never taps the UI (uses keyevent/joystick input only).
#
# Env (defaults):
#   ADB       adb binary                    (adb)
#   ADB_ARGS  extra adb args                (empty), e.g. "-P 5038 -s b225f4aa"
#   GAME      16-hex title id               (needed by get/set/launch/fight/ab)
#   OUT       output dir for logs           (./device-test-out)
#   RUNS      ab: on/off pairs per side     (1)
#   SCREENSHOT_DISPLAY  display id for `shots` (4630946441858561667, the Thor's main screen)
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
#   ab NAME KEY=VALUE [...]   fight NAME-on1 (current settings) and NAME-off1 (KEY=VALUE applied),
#                             repeated RUNS times (on1, off1, on2, off2, ...), restore the old
#                             values, print the summaries and a per-run table with mean/median
#   repeat NAME N             fight NAME-1 .. NAME-N with current settings, then the per-run table
#   wake                     wake the screen (never unlocks)
#   latency NAME [SAMPLES=5]  sample the game layer's SurfaceFlinger --latency SAMPLES times ~2 s apart,
#                             print median/p90 of (present - frame ready) in ms and present gaps >20 ms,
#                             raw samples in $OUT/NAME.lat1 ..
#   shots NAME N              N back-to-back screencaps of the SCREENSHOT_DISPLAY display -> $OUT/NAME-1.png ..
#
# Examples:
#   export GAME=0123456789abcdef
#   ./device-test.sh list
#   ./device-test.sh get
#   ./device-test.sh set fpsCap=60 hd=true
#   ADB_ARGS="-P 5038 -s b225f4aa" ./device-test.sh fight baseline
#   ./device-test.sh summary ./device-test-out/baseline.log
#   ./device-test.sh ab nohd hd=false
#   RUNS=3 ./device-test.sh ab nohd hd=false
#   ./device-test.sh repeat baseline 5

export MSYS_NO_PATHCONV=1   # stop Git Bash from mangling /-prefixed args
set -u

ADB=${ADB:-adb}
ADB_ARGS=${ADB_ARGS:-}
GAME=${GAME:-}
OUT=${OUT:-./device-test-out}
SCREENSHOT_DISPLAY=${SCREENSHOT_DISPLAY:-4630946441858561667}
PKG=io.github.zakuro.android
LOG_FILTER='Zakuro:*'
RESTORE=()

die() { echo "error: $*" >&2; exit 1; }
usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }
adb_cmd() { "$ADB" $ADB_ARGS "$@"; }   # ADB_ARGS is intentionally word-split
need_game() { [[ $GAME =~ ^[0-9A-Fa-f]{16}$ ]] || die "set GAME to a 16-hex title id (got '$GAME')"; }

# settings_call list | get | set JSON -> prints result data; returns 1 if result != 0
settings_call() {
  local remote="am broadcast -n $PKG/.DebugSettings" out code data q sq="'\\''"
  if [[ $1 == list ]]; then
    remote+=" --ez list true"
  else
    remote+=" --es game $GAME"
    # single quotes: the device shell re-parses; a ' inside the JSON is closed, escaped and reopened
    if [[ $1 == set ]]; then
      q=${2//\'/$sq}
      remote+=" --es set '$q'"
    fi
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
kvna() { local v; v=$(kv "$@"); echo "${v/\?/NA}"; }   # like kv, NA when missing (old logs)

need_unlocked() {
  if adb_cmd shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
    echo "device is locked — unlock it"
    exit 3
  fi
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
  need_unlocked
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
    echo "fps=$(kv fps "$line") worst_core_ms=$(kv worst_core_ms "$line") game_fps=$(kvna game_fps "$line") distinct_fps=$(kvna distinct_fps "$line") between=$(kvna between "$line") rough=$(kvna rough "$line") off_beat=$(kvna off_beat "$line") presented_fps=$(kvna presented_fps "$line") speed_percent=$(kvna speed_percent "$line")"
  done)

  echo "== code loads"
  printf '%s\n' "${loads:-(none)}"
  echo "== frame hitches"
  printf '%s\n' "${hitches:-(none)}"
  echo "hitch lines: $cnt, with present_ms>25: $over"
  echo "top 5 core_ms: ${top:-(none)}"
  echo "== benchmark (last 6)"
  printf '%s\n' "${bench:-(none)}"
  echo "== field entry and battle"
  metrics_of "$f"
}

# metrics_of FILE -> field entry and battle metrics as KEY=VALUE lines
# (times are HH:MM:SS.mmm from the logcat line start; date rollover is ignored)
metrics_of() {
  tr -d '\r' < "$1" | awk '
    function tsec(s,   a) { split(s, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
    function fval(line, key,   f, n, i) {
      n = split(line, f, " ")
      for (i = 1; i <= n; i++) if (index(f[i], key "=") == 1) return substr(f[i], length(key) + 2)
      return "NA"
    }
    /code for FieldEffectCommon/ && !fe_seen { fe_seen = 1; fe_t = tsec($2) }
    /code for Battle/ && !bt_seen { bt_seen = 1; bt_t = tsec($2) }
    /frame hitch/ { nh++; ht[nh] = tsec($2); hc[nh] = fval($0, "core_ms") }
    /benchmark/ { nb++; bmt[nb] = tsec($2); bw[nb] = fval($0, "worst_core_ms"); bc[nb] = fval($0, "core_ms") }
    END {
      fe = "NA"; worst = "NA"; mean = "NA"; hl = ""; n = 0; sum = 0
      if (fe_seen)
        for (i = 1; i <= nh; i++)
          if (ht[i] >= fe_t && (!bt_seen || ht[i] < bt_t)) { fe = hc[i]; break }
      if (bt_seen) {
        for (i = 1; i <= nb && n < 2; i++)
          if (bmt[i] >= bt_t) {
            n++
            if (worst == "NA" || bw[i] + 0 > worst + 0) worst = bw[i]
            sum += bc[i] + 0
          }
        if (n) mean = sprintf("%.2f", sum / n)
        for (i = 1; i <= nh; i++)
          if (ht[i] >= bt_t && ht[i] <= bt_t + 3.0) hl = hl (hl == "" ? "" : " ") hc[i]
      }
      if (hl == "") hl = "NA"
      print "field_entry_hitch_core_ms=" fe
      print "battle_worst_core_ms=" worst
      print "battle_hitches=" hl
      print "battle_mean_core_ms=" mean
    }'
}

metric() {   # metric TEXT KEY -> value of KEY= in metrics TEXT, or NA
  local v
  v=$(sed -n "s/^$2=//p" <<<"$1" | head -1)
  echo "${v:-NA}"
}

# run_report SIDE:NAME... -> per-run table from $OUT/NAME.log, then mean/median per side
run_report() {
  local spec side name m rows=""
  for spec in "$@"; do
    side=${spec%%:*}; name=${spec#*:}
    m=$(metrics_of "$OUT/$name.log")
    rows+="$side $name $(metric "$m" battle_worst_core_ms) $(metric "$m" field_entry_hitch_core_ms) $(metric "$m" battle_mean_core_ms)"$'\n'
  done
  awk '
    function isnum(x) { return x ~ /^-?[0-9]+(\.[0-9]+)?$/ }
    function stats(list,   a, n, i, j, t, s) {   # sets MEAN and MED from a space-separated list
      n = split(list, a, " ")
      if (n == 0) { MEAN = "NA"; MED = "NA"; return }
      for (i = 2; i <= n; i++) {
        t = a[i]
        for (j = i - 1; j >= 1 && a[j] + 0 > t + 0; j--) a[j + 1] = a[j]
        a[j + 1] = t
      }
      s = 0
      for (i = 1; i <= n; i++) s += a[i]
      MEAN = sprintf("%.2f", s / n)
      MED = (n % 2) ? sprintf("%.2f", a[(n + 1) / 2]) : sprintf("%.2f", (a[n / 2] + a[n / 2 + 1]) / 2)
    }
    BEGIN { printf "%-20s %22s %26s %22s\n", "run", "battle_worst_core_ms", "field_entry_hitch_core_ms", "battle_mean_core_ms" }
    NF == 5 {
      if (!($1 in seen)) { seen[$1] = 1; order[++ns] = $1 }
      printf "%-20s %22s %26s %22s\n", $2, $3, $4, $5
      if (isnum($3)) W[$1] = W[$1] " " $3
      if (isnum($5)) M[$1] = M[$1] " " $5
    }
    END {
      print ""
      for (k = 1; k <= ns; k++) {
        stats(W[order[k]]); wm = MEAN; wd = MED
        stats(M[order[k]]); mm = MEAN; md = MED
        printf "%s: battle_worst_core_ms mean=%s median=%s; battle_mean_core_ms mean=%s median=%s\n", order[k], wm, wd, mm, md
      }
    }
  ' <<<"$rows"
}

restore_now() {   # EXIT trap for ab: put back the values captured before the test
  (( ${#RESTORE[@]} )) || return 0
  echo "restoring: ${RESTORE[*]}"
  settings_call set "$(json_from "${RESTORE[@]}")" >/dev/null \
    || echo "warning: restore failed, settings may be left changed" >&2
  RESTORE=()
}

cmd_ab() {
  local name=${1:-} kv_arg k old before runs i ON=() specs=()
  [[ $name =~ ^[A-Za-z0-9._-]+$ && $# -gt 1 ]] || die "usage: ab NAME KEY=VALUE [...]"
  runs=${RUNS:-1}
  [[ $runs =~ ^[1-9][0-9]*$ ]] || die "RUNS must be a positive integer (got '$runs')"
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
  ON=("${RESTORE[@]}")   # the on-side values, put back before each later on run
  for i in $(seq 1 "$runs"); do
    if (( i > 1 && ${#ON[@]} )); then set_values "${ON[@]}" >/dev/null || exit 1; fi
    run_fight "$name-on$i"
    set_values "$@" >/dev/null || exit 1
    run_fight "$name-off$i"
    specs+=("on:$name-on$i" "off:$name-off$i")
  done
  restore_now
  for i in $(seq 1 "$runs"); do
    echo "===== $name-on$i (current settings)"
    cmd_summary "$OUT/$name-on$i.log"
    echo "===== $name-off$i ($*)"
    cmd_summary "$OUT/$name-off$i.log"
  done
  run_report "${specs[@]}"
}

cmd_repeat() {
  local name=${1:-} n=${2:-} i specs=()
  [[ $name =~ ^[A-Za-z0-9._-]+$ && $n =~ ^[1-9][0-9]*$ ]] || die "usage: repeat NAME N"
  need_game
  for i in $(seq 1 "$n"); do
    run_fight "$name-$i"
    specs+=("$name:$name-$i")
  done
  run_report "${specs[@]}"
}

# latency_stats FILE... -> "median_ms=.. p90_ms=.. gaps_over_20ms=.. n=.."
# FILE = `dumpsys SurfaceFlinger --latency` output: refresh period, then rows of
# desiredPresent actualPresent frameReady (ns). Rows with actual 0 or INT64_MAX are unfinished.
latency_stats() {
  awk '
    FNR == 1 { prev = 0; next }
    NF == 3 && $2 > 0 && $2 < 9e18 && $3 > 0 && $3 < 9e18 {
      d[++n] = ($2 - $3) / 1e6
      if (prev && $2 - prev > 20e6) gaps++
      prev = $2
    }
    END {
      if (!n) { print "median_ms=NA p90_ms=NA gaps_over_20ms=NA n=0"; exit }
      m = n   # insertion sort, ponytail: O(n^2) fine for ~600 rows
      for (i = 2; i <= m; i++) { t = d[i]; for (j = i - 1; j >= 1 && d[j] > t; j--) d[j + 1] = d[j]; d[j + 1] = t }
      printf "median_ms=%.2f p90_ms=%.2f gaps_over_20ms=%d n=%d\n", \
        (m % 2) ? d[(m + 1) / 2] : (d[m / 2] + d[m / 2 + 1]) / 2, d[int((m - 1) * 0.9) + 1], gaps, m
    }' "$@"
}

cmd_latency() {
  local name=${1:-} n=${2:-5} layer i files=()
  [[ $name =~ ^[A-Za-z0-9._-]+$ && $n =~ ^[1-9][0-9]*$ ]] || die "usage: latency NAME [SAMPLES]"
  need_unlocked
  layer=$(adb_cmd shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep "^$PKG/" | head -1) # the buffer layer: no hash prefix, no Panel:
  [[ -n $layer ]] || layer=$(adb_cmd shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep "$PKG" | head -1)
  [[ -n $layer ]] || die "no SurfaceFlinger layer for $PKG (is the game running?)"
  mkdir -p "$OUT" || die "cannot create $OUT"
  for i in $(seq 1 "$n"); do
    adb_cmd shell dumpsys SurfaceFlinger --latency "'$layer'" | tr -d '\r' > "$OUT/$name.lat$i"
    files+=("$OUT/$name.lat$i")
    (( i < n )) && sleep 2
  done
  echo "layer: $layer"
  latency_stats "${files[@]}"
}

cmd_shots() {
  local name=${1:-} n=${2:-} i
  [[ $name =~ ^[A-Za-z0-9._-]+$ && $n =~ ^[1-9][0-9]*$ ]] || die "usage: shots NAME N"
  need_unlocked
  mkdir -p "$OUT" || die "cannot create $OUT"
  for i in $(seq 1 "$n"); do
    adb_cmd exec-out screencap -p -d "$SCREENSHOT_DISPLAY" > "$OUT/$name-$i.png"
  done
  echo "saved $n screenshots as $OUT/$name-N.png"
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
  repeat)         cmd_repeat "$@" ;;
  latency)        cmd_latency "$@" ;;
  shots)          cmd_shots "$@" ;;
  wake)           adb_cmd shell input keyevent KEYCODE_WAKEUP ;;
  *)              usage; exit 2 ;;
esac
