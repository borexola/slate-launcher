#!/usr/bin/env bash
# Installs and manages the Slate launcher on an Android TV / Google TV device over ADB.
# Run without arguments for the list of commands.
set -euo pipefail
# Plain byte-wise matching everywhere: in other locales a range such as A-Z also matches accented
# letters, and names read from the TV are checked against such ranges.
export LC_ALL=C

PKG=app.slate.launcher
HOME_ACTIVITY="$PKG/.HomeActivity"
GUARD_LONG="$PKG/$PKG.system.HomeGuardService"
GUARD_SHORT="$PKG/.system.HomeGuardService"
FILES="/sdcard/Android/data/$PKG/files"
# Remembers, on the TV itself, which stock packages `takeover` switched off.
RECORD="/data/local/tmp/slate-disabled-packages"
SAVER_LONG="$PKG/$PKG.system.ScreensaverService"
SAVER_SHORT="$PKG/.system.ScreensaverService"
# Remembers, on the TV itself, the screensaver in use before `screensaver-on`.
SAVER_RECORD="/data/local/tmp/slate-screensaver-previous"
# The largest photo Slate accepts (the same as for the wallpaper).
MAX_PHOTO_BYTES=$((16 * 1024 * 1024))
ADB=${ADB:-adb}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Where released builds are published (for `update`). A fork can point this at its own.
RELEASES="${SLATE_RELEASES:-https://github.com/borexola/slate-launcher/releases}"
RELEASE_APK=slate.apk
MAX_APK_BYTES=$((50 * 1024 * 1024))

# Stock home screens that may be switched off once Slate is in place. Order matters: the setup
# helper goes first, so that the real launcher is the last thing standing if anything fails.
SETUP_HELPER=com.google.android.tungsten.setupwraith   # Google TV setup; also answers the Home button
LAUNCHERS=(
  com.google.android.leanbacklauncher       # older Android TV
  com.google.android.tvlauncher             # Android TV
  com.google.android.apps.tv.launcherx      # Google TV
)
STOCK=("$SETUP_HELPER" "${LAUNCHERS[@]}")
usage() {
  cat <<'EOF'
Usage: scripts/slate.sh <command>

  connect <tv-ip>[:port] connect to the TV (enable network debugging on it first)
  install                install or update Slate from the copy built on this computer
  update                 download the latest released Slate and install it (no build needed;
                         also does a first install)
  takeover               make Slate the home screen (switches the stock one off)
  restore                undo takeover: stock home screen back on, guard off, screensaver
                         back to the one used before screensaver-on
  rescue                 restore, and also wipe all of Slate's data: settings, wallpaper,
                         artwork (for when Slate will not start)
  uninstall              restore, then remove Slate (refuses unless a system home screen is
                         on; add --anyway to hand over to a launcher you installed yourself)
  guard-on | guard-off   switch the Home button guard (the fallback for devices where
                         the stock home screen cannot be switched off)
  status                 show what the Home button opens and what is switched off
  watch-next             let Slate read the TV's "watch next" list, for its Continue
                         watching row (the TV can also ask you this itself)
  wallpaper <image>      set the wallpaper (.jpg, .jpeg or .png)
  photos add <folder>    upload the .jpg, .jpeg and .png files in a folder for the
                         screensaver (not its subfolders)
  photos clear           remove the photos uploaded from a computer
  screensaver-on         make Slate's slideshow the TV's screensaver
  screensaver-off        put back the screensaver used before
  icon <package> <png>   replace one app's artwork
  pull-config [file]     copy settings from the TV (export them on the TV first)
  push-config [file]     copy settings to the TV (then import them on the TV)

With several devices attached, set ANDROID_SERIAL to choose one.
EOF
}

die() {
  echo "slate: $*" >&2
  exit 1
}

# Downloads one address into a file. Only https, also after a redirect, and nothing larger than
# an APK could be.
fetch() {
  curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL \
    --max-filesize "$MAX_APK_BYTES" --connect-timeout 20 --max-time 300 --retry 2 -o "$2" "$1"
}

# Prints the version of the latest release, e.g. 0.5.1. GitHub answers ".../releases/latest"
# with a redirect to ".../releases/tag/v<version>", so nothing has to be parsed.
latest_version() {
  local to
  to="$(curl --proto '=https' --tlsv1.2 -fsS -o /dev/null -w '%{redirect_url}' \
    --connect-timeout 20 --max-time 60 "$RELEASES/latest")" || return 1
  case "$to" in */releases/tag/v*) ;; *) return 1 ;; esac
  to="${to##*/releases/tag/v}"
  case "$to" in ''|*[!0-9A-Za-z.]*) return 1 ;; esac
  printf '%s\n' "$to"
}

# Prints the version of the Slate on the TV, or nothing if that cannot be read.
installed_version() {
  dev dumpsys package "$PKG" | LC_ALL=C tr -d '\r' |
    sed -n 's/^ *versionName=\([0-9A-Za-z.]*\)$/\1/p' | head -n 1
}

# Prints a file's SHA-256 in hex.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    die "update needs sha256sum or shasum on this computer, to check the download."
  fi
}

# Quotes one argument for the TV's shell. adb joins its arguments with spaces and the TV parses
# the result a second time, so anything unquoted (a `$` in a class name, say) would be mangled.
rq() {
  printf "'%s'" "${1//\'/\'\\\'\'}"
}

# Runs a command on the TV with every argument passed through exactly as given.
dev() {
  local cmd="" arg
  for arg in "$@"; do cmd+="$(rq "$arg") "; done
  # The answer is passed on exactly as it came. adb adds nothing to it on the Android versions
  # Slate runs on, so a carriage return in it is part of what the TV said, and a name carrying
  # one is turned away like any other odd name.
  "$ADB" shell "$cmd" </dev/null
}

# Runs a multi-line script on the TV as ONE command. The scripts themselves ignore hang-up and
# broken-pipe signals, so once started they finish on the TV even if the connection drops.
# Placeholders are filled from constants in this file only.
# @PREVIOUS@, if a script has it, is filled from the second argument.
dev_script() {
  local script="$1" previous="${2:-}"
  script="${script//@LAUNCHERS@/${LAUNCHERS[*]}}"
  script="${script//@STOCK@/${STOCK[*]}}"
  script="${script//@RECORD@/$RECORD}"
  script="${script//@HOME@/$HOME_ACTIVITY}"
  script="${script//@PKG@/$PKG}"
  script="${script//@HELPER@/$SETUP_HELPER}"
  # The one value not from this file: a component name, checked by is_component before it gets here.
  # (Spliced in, not substituted: no character in it means anything that way, on any bash.)
  case "$script" in
    *@PREVIOUS@*) script="${script%%@PREVIOUS@*}$previous${script#*@PREVIOUS@}" ;;
  esac
  "$ADB" shell "$script" </dev/null
}

require_device() {
  local state
  state="$("$ADB" get-state 2>/dev/null || true)"
  [ "$state" = device ] || die "no TV reachable over ADB (state: ${state:-none}).
  Run: scripts/slate.sh connect <tv-ip>   and accept the prompt on the TV.
  With several devices attached, set ANDROID_SERIAL to choose one."
}

# Is the package installed, whether enabled or not?
has() {
  local out
  out="$(dev pm list packages "$1")" || die "could not list packages on the TV"
  grep -qxF "package:$1" <<<"$out"
}

is_disabled() {
  local out
  out="$(dev pm list packages -d "$1")" || die "could not list packages on the TV"
  grep -qxF "package:$1" <<<"$out"
}

# For when the TV did not answer a question whose answer decides what happens next.
no_answer() {
  die "the TV did not answer (lost contact?), so nothing further was done. Check with: scripts/slate.sh status"
}

# Slate's user id on the TV: the number Android gave the app when it was installed. Fails if
# the TV did not say.
slate_uid() {
  local out line pattern="^package:${PKG//./\\.} uid:([0-9]+)(,[0-9]+)*\$"
  out="$(dev cmd package list packages -U "$PKG" 2>/dev/null)" || return 1
  while IFS= read -r line; do
    if [[ "$line" =~ $pattern ]]; then
      printf '%s' "${BASH_REMATCH[1]}"
      return 0
    fi
  done <<<"$out"
  return 1
}

read -r -d '' SLATE_PIDS_SCRIPT <<'EOS' || true
set -f
unknown=0
for pid in $(pidof @PKG@ 2>/dev/null); do
  case "$pid" in "" | *[!0-9]*) continue ;; esac
  owner="$(stat -c %u "/proc/$pid" 2>/dev/null)"
  case "$owner" in
    "") if [ -d "/proc/$pid" ]; then unknown=1; fi ;;
    "@UID@") echo "$pid" ;;
  esac
done
if [ "$unknown" = 1 ]; then echo owner-unknown; fi
echo pids-done
EOS

# The ids of Slate's own processes, on one line; a changed id means the process died and was
# started again. A process counts as Slate's by who owns it, which Android decides: the name is
# the app's own choice, and another app may give its process the same one. Takes Slate's user
# id. Fails with 1 if the TV did not answer, with 2 if it could not say who owns a process.
slate_pids() {
  local uid="$1" out script
  script="${SLATE_PIDS_SCRIPT//@PKG@/$PKG}"
  script="${script//@UID@/$uid}"
  out="$("$ADB" shell "$script" </dev/null)" || return 1
  case "$out" in
    *pids-done) ;;
    *) return 1 ;;
  esac
  out="${out%pids-done}"
  case $'\n'"$out" in
    *$'\n'owner-unknown$'\n'*) return 2 ;;
  esac
  printf '%s' "$(tr '\n' ' ' <<<"$out" | sed 's/ *$//')"
}

# Is one of Slate's screens showing right now? Android is asked for the state of Slate's user
# id, where 2 ("top") means just that. Nothing another app prints, or calls itself, is part of
# the answer. Takes Slate's user id. No answer counts as no.
slate_on_screen() {
  local uid="$1" out
  out="$(dev am get-uid-state "$uid" 2>/dev/null)" || return 1
  [ "${out%% *}" = 2 ]
}

# 0: Slate has a process. 1: it has none. 2: the TV did not say.
is_running() {
  local uid pids
  uid="$(slate_uid)" || return 2
  pids="$(slate_pids "$uid")" || return 2
  [ -n "$pids" ]
}

# A component as Android prints it, package/activity, made only of the characters such names
# are built from. An installed app chooses its own names; one written to confuse is not accepted.
is_component() {
  local pattern='^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$'
  [[ "$1" =~ $pattern ]]
}

# Could this name be a home screen proper? Not Slate itself, and not the setup and boot screens
# that also answer the Home button: none of those is something to leave the TV with.
is_real_home() {
  is_component "$1" || return 1
  case "$1" in
    "$PKG"/* | "$SETUP_HELPER"/* | *FallbackHome* | *[Ss]etup[Ww]izard* | *.provision/*) return 1 ;;
  esac
  return 0
}

# Removes leading spaces and tabs, and nothing else: a name with anything odd after it must not
# pass for the clean name.
ltrim() {
  local value="$1"
  value="${value#"${value%%[![:blank:]]*}"}"
  printf '%s' "$value"
}

# What Android would open for the Home button, as package/activity. Prints nothing unless the
# answer has exactly the shape Android gives it: one line of details, one line with the name.
# (An app chooses its own activity names, and one containing a line break would otherwise add a
# line that reads like another app's name.) Fails if the TV could not be asked, so that "no
# answer" is never mistaken for "no home screen".
current_home() {
  local out line first="" second="" count=0
  out="$(dev cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN)" || return 1
  while IFS= read -r line; do
    count=$((count + 1))
    if [ "$count" = 1 ]; then first="$(ltrim "$line")"; fi
    if [ "$count" = 2 ]; then second="$(ltrim "$line")"; fi
  done <<<"$out"
  if [ "$count" = 2 ] && [[ "$first" == priority=* ]] && is_component "$second"; then
    printf '%s\n' "$second"
  fi
}

# Every home screen activity Android lists, one per line. What is in the list is only a hint:
# the names are chosen by the apps themselves. Use is_home_screen to confirm one.
listed_homes() {
  dev cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null
}

# Is this component a home screen proper that is switched on right now? Android is asked again
# about that one package alone. Only the package itself can appear in that answer, so another
# app cannot make it look as if this one had a home screen. This also turns away Android's
# "choose a home app" dialog, which is not a home screen of any package.
is_home_screen() {
  local out line
  is_real_home "$1" || return 1
  out="$(dev cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME -p "${1%%/*}" 2>/dev/null)" || no_answer
  while IFS= read -r line; do
    if [ "$(ltrim "$line")" = "$1" ]; then return 0; fi
  done <<<"$out"
  return 1
}

# Is a home screen other than Slate switched on? With "system": one that is part of the system.
# This asks for an actual home screen activity, not merely an installed package: some TVs carry
# an empty package under a launcher's name. What was found is left in FOUND_HOME: the first
# system one, or with "any" every one, separated by commas. Not for use inside $( ): it stops
# the script if the TV does not answer.
FOUND_HOME=""
other_home_exists() {
  local out system line
  FOUND_HOME=""
  out="$(listed_homes)" || no_answer
  system="$(dev pm list packages -s 2>/dev/null)" || no_answer
  [ -n "$system" ] || no_answer
  while IFS= read -r line; do
    line="$(ltrim "$line")"
    if [ "$1" = system ] && ! grep -qxF "package:${line%%/*}" <<<"$system"; then continue; fi
    if is_home_screen "$line"; then
      FOUND_HOME+="${FOUND_HOME:+, }$line"
      if [ "$1" = system ]; then return 0; fi
    fi
  done <<<"$out"
  [ -n "$FOUND_HOME" ]
}

# Is this package part of the system, as opposed to something installed later? Stops the script
# if the TV does not answer.
is_system_package() {
  local system
  system="$(dev pm list packages -s 2>/dev/null)" || no_answer
  [ -n "$system" ] || no_answer
  grep -qxF "package:$1" <<<"$system"
}

# Prints text that came from the TV with anything unprintable removed.
show() {
  printf '%s\n' "$1" | LC_ALL=C tr -cd '[:print:]\n'
}

# Uploads a file, staying quiet unless it fails.
push() {
  local out
  if ! out="$("$ADB" push "$1" "$2" 2>&1)"; then
    show "$out" >&2
    die "the upload failed"
  fi
}

require_slate() {
  has "$PKG" || die "Slate is not installed on the TV. Run: scripts/slate.sh install"
}

# The app creates its own folder on first start. Creating it from here could leave it with an
# owner the app cannot use.
require_app_folder() {
  dev ls "$FILES" >/dev/null 2>&1 || die "Slate's folder on the TV cannot be reached: it does not exist yet (open Slate once, then
  try again), or this TV does not let adb into apps' folders (seen on an Android 11 emulator)."
}

find_apk() {
  local release="$ROOT/app/build/outputs/apk/release/app-release.apk"
  local debug="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$release" ] && [ -f "$debug" ]; then
    if [ "$debug" -nt "$release" ]; then echo "$debug"; else echo "$release"; fi
  elif [ -f "$release" ]; then
    echo "$release"
  elif [ -f "$debug" ]; then
    echo "$debug"
  else
    die "no APK found. Build one first: ./gradlew assembleRelease"
  fi
}

# @PREVIOUS@ is the home screen in use before takeover, or nothing. It goes into the record too,
# so that restore can go back to exactly that one: its name as one word no package name can be
# equal to, and after it when that app was first installed. An update leaves that time as it
# is; removing the app and installing another under the same name does not, which is how this
# installation is told from an app that might later carry its name.
#
# It is not recorded while an earlier takeover is still in force (something in the record is
# still switched off): what Home opens then is not something the owner arranged before Slate.
#
# Nothing at all is changed unless the record can be kept.
read -r -d '' TAKEOVER_SCRIPT <<'EOS' || true
trap '' HUP PIPE
set -f
previous='@PREVIOUS@'
system_packages="$(pm list packages -s 2>/dev/null)"
is_system() {
  for s_word in $system_packages; do
    if [ "$s_word" = "package:$1" ]; then return 0; fi
  done
  return 1
}
record_ok=1
# The record exists from here on, even if nothing ends up in it: restore reads "takeover ran and
# switched off exactly these" differently from "there is no record".
if ! touch @RECORD@ 2>/dev/null; then record_ok=0; fi
earlier=0
if [ "$record_ok" = 1 ]; then
  for r_word in $(cat @RECORD@ 2>/dev/null); do
    for q in @STOCK@; do
      if [ "$r_word" = "$q" ]; then
        for d_word in $(pm list packages -d "$q" 2>/dev/null); do
          if [ "$d_word" = "package:$q" ]; then earlier=1; fi
        done
      fi
    done
  done
fi
# When a package was first installed, as one word, or nothing if the TV does not say.
installed_since() {
  # Every such line counts, not only the first: an app can get further lines into what Android
  # prints about it, but cannot take the true one out.
  i_stamp=""
  for i_word in $(dumpsys package "$1" 2>/dev/null | grep "firstInstallTime="); do
    i_stamp="${i_stamp:+${i_stamp}_}${i_word#firstInstallTime=}"
  done
  case "$i_stamp" in
    *[!0-9:_-]*) i_stamp="" ;;
  esac
  if [ "${#i_stamp}" -gt 200 ]; then i_stamp=""; fi
  echo "$i_stamp"
}
if [ "$record_ok" = 1 ] && [ -n "$previous" ] && [ "$earlier" = 0 ]; then
  previous_since="$(installed_since "${previous%%/*}")"
  if ! { echo "previous-home=$previous" >> @RECORD@; } 2>/dev/null; then record_ok=0; fi
  if [ "$record_ok" = 1 ] && [ -n "$previous_since" ]; then
    if ! { echo "previous-since=$previous_since" >> @RECORD@; } 2>/dev/null; then record_ok=0; fi
  fi
fi
if [ "$record_ok" = 0 ]; then
  echo "  could not keep a record on the TV of what gets changed, so nothing was changed"
elif [ -z "$system_packages" ]; then
  # Without that list a stock name cannot be told from something an owner installed under it.
  echo "  the TV did not list its system packages, so nothing was changed"
else
  for p in @STOCK@; do
    for listed in $(pm list packages -e "$p" 2>/dev/null); do
      if [ "$listed" = "package:$p" ]; then
        if ! is_system "$p"; then
          # Something an owner installed under a stock name is not the stock home screen.
          echo "  left alone: $p is not part of the system on this TV"
        # Written down before it is switched off, so that it can always be switched back on.
        # What cannot be written down (the TV's storage is full, say) stays on.
        elif { echo "$p" >> @RECORD@; } 2>/dev/null; then
          if pm disable-user --user 0 "$p" >/dev/null 2>&1; then
            echo "  switched off $p"
          else
            echo "  could not switch off $p (this device may not allow it)"
          fi
        else
          echo "  could not record $p, so it was left on"
        fi
      fi
    done
  done
  cmd package set-home-activity @HOME@ >/dev/null 2>&1
fi
echo "takeover-finished"
exit 0
EOS

# Hands the Home button back. Whether that worked is judged by what Android now does with Home,
# whoever the home screen belongs to: this script knows the Google launchers by name, but a TV
# may have a manufacturer's own.
#
#  1. Switch on the launchers takeover recorded switching off.
#  2. If Home has not come back, and there is no record or no home screen at all is on, switch
#     on every known launcher.
#  3. Switch the setup helper back on only beside a home screen that was there with it before:
#     a system one, or the one in use before takeover (the helper answers the Home button
#     too, and must not be the only thing that does). Where it is left off, it is named with
#     how to switch it on by hand.
#  4. If Home has still not come back, and takeover recorded which home screen was in use
#     before, and that very installation is still there, tell Android to use it again: that is
#     what undoing means. A home screen the owner has moved to since is left alone.
#  5. If Android still prefers Slate and a system home screen is on, tell Android to use it.
#     A home screen that is not part of the system is never chosen here unless it is the one
#     from step 4, which the owner had chosen themselves.
#  6. Report: where Home goes now, and anything known to this script that is still switched off.
#     The record is kept for as long as something in it is still off.
#
# A "home screen" here always means an actual home activity, never just an installed package,
# and never Slate or a setup or boot screen. Activity names are chosen by the apps themselves,
# so a name read from the TV counts only if it consists of the characters such names are made
# of AND Android lists it again when asked about that one package alone (see `confirmed`).
#
# Every function uses variable names of its own: in this shell all variables are shared, and a
# function called inside another's loop must not be able to change that loop's variable.
#
# The last line is one this script looks for, so that silence can only mean the connection was
# lost. It carries three yes/no answers: was anything switched, was Home handed to another home
# screen, did anything fail.
read -r -d '' RESTORE_SCRIPT <<'EOS' || true
trap '' HUP PIPE
set -f
failed=0
changed=0
tried=""
system_packages="$(pm list packages -s 2>/dev/null)"
enable_one() {
  case " $tried " in *" $1 "*) return 0 ;; esac
  tried="$tried $1"
  # Only what takeover recorded, or what is part of the system: under a stock name there may be
  # something the owner installed and switched off themselves. (If the TV did not say what is
  # part of the system, nothing is held back: switching back on comes first.)
  if [ -n "$system_packages" ] && ! is_recorded "$1" && ! is_system "$1"; then return 0; fi
  for e_word in $(pm list packages -d "$1" 2>/dev/null); do
    if [ "$e_word" = "package:$1" ]; then
      if pm enable "$1" >/dev/null 2>&1; then
        echo "  switched on $1"
        changed=1
        if [ "$1" = "@HELPER@" ]; then helper_switched=1; fi
      else
        echo "  could not switch on $1"
        failed=1
      fi
    fi
  done
}
is_recorded() {
  for r_word in $recorded; do
    if [ "$r_word" = "$1" ]; then return 0; fi
  done
  return 1
}
plain() {
  case "$1" in
    "" | *[!A-Za-z0-9_./\$]*) return 1 ;;
    */*) return 0 ;;
  esac
  return 1
}
real_home() {
  if ! plain "$1"; then return 1; fi
  case "$1" in
    "@PKG@/"* | "@HELPER@/"* | *FallbackHome* | *[Ss]etup[Ww]izard* | *.provision/*) return 1 ;;
  esac
  return 0
}
is_system() {
  for s_word in $system_packages; do
    if [ "$s_word" = "package:$1" ]; then return 0; fi
  done
  return 1
}
# Every home screen activity Android lists. Only a hint; see `confirmed`.
home_activities() {
  cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null
}
# Is this a home screen proper that is switched on right now? Android is asked again about that
# one package alone. Only the package itself can appear in that answer, so another app cannot
# make it look as if this one had a home screen. This also turns away Android's "choose a home
# app" dialog, which is not a home screen of any package.
confirmed() {
  if ! real_home "$1"; then return 1; fi
  for c_word in $(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME -p "${1%%/*}" 2>/dev/null); do
    if [ "$c_word" = "$1" ]; then return 0; fi
  done
  return 1
}
# What Android would open for Home right now. Prints nothing unless the answer has exactly the
# shape Android gives it: one line of details, then one line with the name.
home_now() {
  n_count=0
  n_first=""
  n_second=""
  n_ifs="$IFS"
  IFS='
'
  for n_line in $(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null); do
    n_count=$((n_count + 1))
    if [ "$n_count" = 1 ]; then n_first="$n_line"; fi
    if [ "$n_count" = 2 ]; then n_second="$n_line"; fi
  done
  IFS="$n_ifs"
  n_first="${n_first#"${n_first%%[! ]*}"}"
  n_second="${n_second#"${n_second%%[! ]*}"}"
  case "$n_first" in
    priority=*) ;;
    *) return 0 ;;
  esac
  if [ "$n_count" = 2 ] && plain "$n_second"; then echo "$n_second"; fi
}
home_is_back() {
  confirmed "$(home_now)"
}
# The first home screen proper that is switched on. With "system": only one that is part of the system.
other_home() {
  for o_word in $(home_activities); do
    if ! real_home "$o_word"; then continue; fi
    if [ "$1" = system ] && ! is_system "${o_word%%/*}"; then continue; fi
    if confirmed "$o_word"; then
      echo "$o_word"
      return 0
    fi
  done
  return 1
}
have_record=0
recorded=""
previous=""
if [ -f @RECORD@ ]; then
  # A record that cannot be read is no record: nothing is known about what takeover did.
  if recorded="$(cat @RECORD@ 2>/dev/null)"; then have_record=1; else recorded=""; fi
fi
previous_since=""
for r_word in $recorded; do
  case "$r_word" in
    previous-home=*)
      previous="${r_word#previous-home=}"
      previous_since=""
      ;;
    previous-since=*) previous_since="${r_word#previous-since=}" ;;
  esac
done
helper_switched=0
for p in @LAUNCHERS@; do
  if is_recorded "$p"; then enable_one "$p"; fi
done
if ! home_is_back; then
  if [ "$have_record" = 0 ] || ! other_home any >/dev/null; then
    for p in @LAUNCHERS@; do enable_one "$p"; done
  fi
fi
# Prints the command that makes a home screen the one in use, for the owner to run. Quoted so
# that a `$` in a class name survives both this computer's shell and the TV's.
by_hand() {
  echo "  adb shell 'cmd package set-home-activity \"$(printf '%s' "$1" | sed 's/\$/\\$/g')\"'"
}
# When a package was first installed, as one word, or nothing if the TV does not say. An update
# leaves that time alone; a different app installed under the same name has a later one.
installed_since() {
  # Every such line counts, not only the first: an app can get further lines into what Android
  # prints about it, but cannot take the true one out.
  i_stamp=""
  for i_word in $(dumpsys package "$1" 2>/dev/null | grep "firstInstallTime="); do
    i_stamp="${i_stamp:+${i_stamp}_}${i_word#firstInstallTime=}"
  done
  case "$i_stamp" in
    *[!0-9:_-]*) i_stamp="" ;;
  esac
  if [ "${#i_stamp}" -gt 200 ]; then i_stamp=""; fi
  echo "$i_stamp"
}
# Is the home screen from before takeover there, and the same installation of it as then?
# previous_differs: 1 if it was installed anew since, 2 if that could not be told.
previous_ok=0
previous_differs=0
if confirmed "$previous"; then
  now_since="$(installed_since "${previous%%/*}")"
  if is_system "${previous%%/*}"; then
    previous_ok=1
  elif [ -z "$previous_since" ] || [ -z "$now_since" ]; then
    previous_differs=2
  elif [ "$now_since" = "$previous_since" ]; then
    previous_ok=1
  else
    previous_differs=1
  fi
fi
# The helper comes back only beside a home screen that was there with it before: a system one,
# or the one from before takeover. Never merely because some home screen is in use: the helper
# answers the Home button too, and an owner who runs a launcher of their own may have switched
# it off themselves. With no record to go by, it is left alone while the Home button opens a
# launcher that is not part of the system.
own_home=0
home_at_start="$(home_now)"
if confirmed "$home_at_start" && ! is_system "${home_at_start%%/*}"; then own_home=1; fi
if [ -n "$(other_home system)" ] || [ "$previous_ok" = 1 ]; then
  if is_recorded "@HELPER@" || { [ "$have_record" = 0 ] && [ "$own_home" = 0 ]; }; then enable_one "@HELPER@"; fi
fi
# The owner has put the home screen from before takeover back by hand, after it could not be
# confirmed as the same installation. Should the helper still be off then, it is named below
# with how to switch it on, and that settles the record.
helper_left=0
if [ "$previous_differs" != 0 ] && [ "$home_at_start" = "$previous" ] && [ "$previous_ok" = 0 ]; then helper_left=1; fi
# Telling Android which home screen to use counts as a change to be saved, like a switch.
asked_previous=0
if ! home_is_back && [ "$previous_ok" = 1 ]; then
  cmd package set-home-activity "$previous" >/dev/null 2>&1
  changed=1
  asked_previous=1
fi
if ! home_is_back; then
  other="$(other_home system)"
  if [ -n "$other" ]; then
    cmd package set-home-activity "$other" >/dev/null 2>&1
    changed=1
  fi
fi
# The helper answers the Home button too. Should Android have sent Home to it although it was
# asked for a home screen, the helper goes back off, so that the TV is not left on something
# that is no home screen. Only when this run switched it on and takeover recorded it: it stays
# in the record (the record is kept, see below), and a later restore switches it on again.
if [ "$helper_switched" = 1 ] && is_recorded "@HELPER@"; then
  case "$(home_now)" in
    "@HELPER@/"*)
      if pm disable-user --user 0 @HELPER@ >/dev/null 2>&1; then
        echo "  Home went to the setup helper, so it is switched off again for now"
        changed=1
        # Not all is back as it was: said so, and the record is kept.
        failed=1
      fi
      ;;
  esac
fi
now="$(home_now)"
handed=0
if confirmed "$now"; then
  handed=1
  echo "  Home now opens $now"
else
  case "$now" in
    "@PKG@/"*) still="Home still opens Slate" ;;
    "@HELPER@/"*) still="Home opens the setup helper, which is not a home screen" ;;
    *) still="Android has not settled on a home screen" ;;
  esac
  other="$(other_home system)"
  if [ -n "$other" ]; then
    echo "  $still, although a system home screen is on: $other"
    echo "  Android was asked to use it and did not. Run restore again; removing Slate"
    echo "  (scripts/slate.sh uninstall) also hands the Home button to it."
  else
    other="$(other_home any)"
    if [ "$asked_previous" = 1 ]; then
      echo "  $still. Android was asked to use the home screen from before takeover,"
      echo "  $previous, and did not. Run restore again, or remove Slate"
      echo "  (scripts/slate.sh uninstall --anyway)."
    elif [ "$previous_differs" != 0 ]; then
      # Said in full just below.
      echo "  $still."
    elif [ -n "$other" ]; then
      echo "  $still. Another home screen is on, $other, but it is not part"
      echo "  of the system, so Android was not told to use it. If it is the one you want, either"
      echo "  remove Slate (scripts/slate.sh uninstall --anyway) or tell Android yourself:"
      by_hand "$other"
    else
      echo "  no other home screen could be switched on"
    fi
  fi
  failed=1
fi
if [ "$previous_differs" != 0 ] && [ "$now" != "$previous" ]; then
  if [ "$previous_differs" = 1 ]; then
    echo "  The home screen in use before takeover, $previous, appears to have been"
    echo "  installed anew since (its install time reads differently), so Android was not told to"
    echo "  use it. If it is the right one:"
  else
    echo "  The home screen in use before takeover, $previous, could not be confirmed"
    echo "  as the same installation, so Android was not told to use it. If it is the right one:"
  fi
  by_hand "$previous"
fi
left=0
any_off=0
for p in @STOCK@; do
  # Something an owner installed under a stock name, and never recorded, is none of this script's business.
  if [ -n "$system_packages" ] && ! is_system "$p" && ! is_recorded "$p"; then continue; fi
  for d_word in $(pm list packages -d "$p" 2>/dev/null); do
    if [ "$d_word" = "package:$p" ]; then
      echo "  still switched off: $p"
      any_off=1
      if is_recorded "$p" && ! { [ "$p" = "@HELPER@" ] && [ "$helper_left" = 1 ]; }; then left=1; fi
    fi
  done
done
if [ "$any_off" = 1 ]; then echo "  (to switch one on by hand: adb shell pm enable <its name>)"; fi
if [ "$failed" = 0 ] && [ "$left" = 0 ]; then rm -f @RECORD@ 2>/dev/null; fi
echo "restore-finished $changed $handed $failed"
exit $failed
EOS

# Prints the TV's enabled accessibility services, refusing to continue if the value holds
# anything this script would not be able to write back safely.
read_services() {
  local value pattern='^[A-Za-z0-9._/:$]*$'
  value="$(dev settings get secure enabled_accessibility_services)" || die "could not read the TV's accessibility settings"
  if [ "$value" = null ]; then value=""; fi
  [[ "$value" =~ $pattern ]] || die "the TV's accessibility service list contains unexpected characters, so it is left untouched.
  Switch the guard on the TV instead: Settings > System > Accessibility."
  printf '%s' "$value"
}

# Prints the given colon-separated list without Slate's guard (in either spelling).
without_guard() {
  local entry result=""
  local IFS=:
  for entry in $1; do
    if [ -n "$entry" ] && [ "$entry" != "$GUARD_LONG" ] && [ "$entry" != "$GUARD_SHORT" ]; then
      result+="${result:+:}$entry"
    fi
  done
  printf '%s' "$result"
}

guard_is_on() {
  local value
  value="$(read_services)" || exit 1
  case ":$value:" in
    *":$GUARD_LONG:"* | *":$GUARD_SHORT:"*) return 0 ;;
    *) return 1 ;;
  esac
}

guard_off() {
  local current rest
  # Explicit exits: this runs inside `if`/`||` in places, where `set -e` does not apply.
  current="$(read_services)" || exit 1
  rest="$(without_guard "$current")"
  if [ "$rest" = "$current" ]; then return 0; fi
  if [ -z "$rest" ]; then
    dev settings delete secure enabled_accessibility_services >/dev/null || exit 1
  else
    dev settings put secure enabled_accessibility_services "$rest" || exit 1
  fi
  if guard_is_on; then die "the Home button guard is still on; switch it off on the TV under Settings > System > Accessibility"; fi
}

guard_on() {
  local current rest
  current="$(read_services)" || exit 1
  rest="$(without_guard "$current")"
  dev settings put secure enabled_accessibility_services "${rest:+$rest:}$GUARD_LONG" || exit 1
  dev settings put secure accessibility_enabled 1 || exit 1
  guard_is_on || die "the TV did not accept the change; switch the guard on under Settings > System > Accessibility"
}

status() {
  local p state
  local home
  if home="$(current_home)"; then
    case "$home" in
      "") echo "Home button opens:  nothing recognisable" ;;
      android/*) echo "Home button opens:  not settled (Android would ask which home screen to use)" ;;
      *) echo "Home button opens:  $home" ;;
    esac
  else
    echo "Home button opens:  unknown (the TV did not answer)"
  fi
  if has "$PKG"; then
    local running=0
    is_running || running=$?
    case "$running" in
      0) state="installed, running" ;;
      1) state="installed, not running" ;;
      *) state="installed (the TV did not say whether it is running)" ;;
    esac
  else
    state="not installed"
  fi
  echo "Slate:              $state"
  if guard_is_on; then state=on; else state=off; fi
  echo "Home button guard:  $state"
  local saver saver_on_off
  if saver="$(read_saver_setting screensaver_components 2>/dev/null)" && saver_on_off="$(read_saver_setting screensaver_enabled 2>/dev/null)"; then
    if [ "$saver_on_off" = 0 ]; then
      state="switched off"
    elif saver_is_slate "$saver"; then
      state="Slate's slideshow"
    elif [ "$saver" = null ]; then
      state="the TV's default"
    else
      state="$saver"
    fi
  else
    state="unknown (the TV did not answer, or its setting is unusual)"
  fi
  echo "Screensaver:        $state"
  echo "Stock home screens:"
  local system note
  system="$(dev pm list packages -s 2>/dev/null || true)"
  for p in "${STOCK[@]}"; do
    if has "$p"; then
      # Under a stock name there may be something the owner installed, on a TV without the real
      # one. (Only said if the TV did list what is part of the system.)
      note=""
      if [ -n "$system" ] && ! grep -qxF "package:$p" <<<"$system"; then note=", not part of the system on this TV"; fi
      if is_disabled "$p"; then echo "  $p  (switched off$note)"; else echo "  $p  (on$note)"; fi
    fi
  done
}

# Android writes "this package is switched on/off" to storage about ten seconds after being
# told, and there is no command to hurry it. A reboot or power cut before then silently undoes
# the change, so wait it out before reporting anything as done.
settle() {
  echo "Waiting for Android to save the change (keep the TV powered on)..."
  sleep 15
}

# Does a short command still get through to the TV and back?
link_ok() {
  [ "$("$ADB" shell "echo delivery-ok" </dev/null 2>/dev/null || true)" = delivery-ok ]
}

# Stops if the given path on the TV is a folder where a file belongs.
refuse_folder() {
  if dev test -d "$1" >/dev/null 2>&1; then
    die "on the TV, $1 is a folder, which does not belong there. Remove it and try again."
  fi
}

# For takeover, when Slate's processes could not be listed. Takes slate_pids' exit status.
pids_failed() {
  if [ "$1" = 2 ]; then
    die "this TV did not say who owns Slate's process, so it cannot be told whether Slate stays up.
  Nothing was changed."
  fi
  die "lost contact with the TV. Nothing was changed."
}

# Can this computer's adb deliver a command as long as the one that undoes takeover? Some old
# adb versions refuse long commands. Takeover asks first: finding out afterwards would be too late.
can_send_restore() {
  local padding out
  padding="$(printf '%*s' $((${#RESTORE_SCRIPT} + 1000)) '' | tr ' ' x)"
  out="$("$ADB" shell ": $padding; echo delivery-ok" </dev/null 2>/dev/null)" || return 1
  [ "$out" = delivery-ok ]
}

# Switches the stock home screen back on. Leaves the Home button guard alone. Afterwards
# RESTORE_HANDED says whether the Home button now goes to another home screen.
RESTORE_HANDED=0
restore_stock() {
  local out last shown changed failed pattern='^restore-finished ([01]) ([01]) ([01])$'
  RESTORE_HANDED=0
  echo "Handing the Home button back..."
  # What counts is the script's own last line, not how the connection ended: the script on the
  # TV always finishes with it, so not hearing it means the connection dropped.
  out="$(dev_script "$RESTORE_SCRIPT")" || true
  last="${out##*$'\n'}"
  if ! [[ "$last" =~ $pattern ]]; then
    if [ -z "$out" ] && link_ok && ! can_send_restore; then
      die "this computer's adb could not deliver the command that hands the Home button back (an old
  adb version?). Update the Android platform-tools and run this again. Nothing was changed."
    fi
    die "lost contact with the TV. Run: scripts/slate.sh status   then, if needed: scripts/slate.sh restore"
  fi
  changed="${BASH_REMATCH[1]}"
  RESTORE_HANDED="${BASH_REMATCH[2]}"
  failed="${BASH_REMATCH[3]}"
  shown="$(grep -v "^restore-finished " <<<"$out" || true)"
  if [ -n "$shown" ]; then show "$shown"; fi
  # Only worth waiting if something was actually switched.
  if [ "$changed" = 1 ]; then settle; fi
  return "$failed"
}

# What to say when restore_stock reported a failure.
restore_complaint() {
  if [ "$RESTORE_HANDED" = 1 ]; then
    echo "the Home button was handed back, but not everything could be switched back on (see above)."
  else
    echo "the Home button was not handed back (see above)."
  fi
}

# ---- screensaver ----

# Prints one of the TV's screensaver settings ("null" when it is not set), refusing to go on if
# it holds anything a list of component names, or a number, does not.
read_saver_setting() {
  local saver_value saver_pattern='^[A-Za-z0-9._/$,]*$'
  saver_value="$(dev settings get secure "$1")" || die "could not read the TV's screensaver settings"
  [[ "$saver_value" =~ $saver_pattern ]] || die "the TV's $1 setting contains unexpected characters, so it is left untouched."
  printf '%s' "$saver_value"
}

# Does this list of components name Slate's screensaver (in either spelling)?
saver_is_slate() {
  case ",$1," in
    *",$SAVER_LONG,"* | *",$SAVER_SHORT,"*) return 0 ;;
    *) return 1 ;;
  esac
}

# Reads the record of the screensaver used before into SAVED_COMPONENTS and SAVED_ENABLED.
# Fails with 1 if there is none. Stops the script if the TV does not answer or the record is
# not one this script wrote.
SAVED_COMPONENTS=""
SAVED_ENABLED=""
read_saver_record() {
  local record_out record_pattern='^components=(null|[A-Za-z0-9._/$,]*)'$'\n''enabled=(null|[0-9]*)$'
  record_out="$("$ADB" shell "if [ -f $SAVER_RECORD ]; then cat $SAVER_RECORD; echo record-end; else echo no-record; fi" </dev/null 2>/dev/null)" || no_answer
  case "$record_out" in
    no-record) return 1 ;;
    *$'\n'record-end) record_out="${record_out%$'\n'record-end}" ;;
    *) no_answer ;;
  esac
  record_out="${record_out%$'\n'}"
  [[ "$record_out" =~ $record_pattern ]] || die "the record of the earlier screensaver ($SAVER_RECORD on the TV) is not one this script wrote,
  so nothing was changed. Remove it (adb shell rm $SAVER_RECORD) and try again."
  SAVED_COMPONENTS="${BASH_REMATCH[1]}"
  SAVED_ENABLED="${BASH_REMATCH[2]}"
}

# Keeps the given screensaver settings (already checked) on the TV, and reads them back.
write_saver_record() {
  local write_out
  write_out="$("$ADB" shell "printf 'components=%s\nenabled=%s\n' $(rq "$1") $(rq "$2") > $SAVER_RECORD.tmp && mv $SAVER_RECORD.tmp $SAVER_RECORD && echo record-ok" </dev/null 2>/dev/null)" || true
  [ "$write_out" = record-ok ] || return 1
  read_saver_record || return 1
  [ "$SAVED_COMPONENTS" = "$1" ] && [ "$SAVED_ENABLED" = "$2" ]
}

# Sets one screensaver setting to a value read earlier ("null": not set at all), and checks it.
put_saver_setting() {
  local put_name="$1" put_value="$2" put_now
  if [ "$put_value" = null ]; then
    dev settings delete secure "$put_name" >/dev/null 2>&1 || return 1
  else
    dev settings put secure "$put_name" "$put_value" >/dev/null 2>&1 || return 1
  fi
  put_now="$(read_saver_setting "$put_name")" || exit 1
  [ "$put_now" = "$put_value" ]
}

# Is Slate's screensaver part of the installed Slate? (Builds before 0.5 have none.)
saver_installed() {
  local query_out query_line
  query_out="$(dev cmd package query-services --brief -a android.service.dreams.DreamService 2>/dev/null)" || no_answer
  while IFS= read -r query_line; do
    query_line="$(ltrim "$query_line")"
    if [ "$query_line" = "$SAVER_SHORT" ] || [ "$query_line" = "$SAVER_LONG" ]; then return 0; fi
  done <<<"$query_out"
  return 1
}

saver_on() {
  local on_components on_enabled on_sleep
  on_components="$(read_saver_setting screensaver_components)" || exit 1
  on_enabled="$(read_saver_setting screensaver_enabled)" || exit 1
  [[ "$on_enabled" =~ ^(null|[0-9]+)$ ]] || die "the TV's screensaver_enabled setting is not a number, so it is left untouched."
  if [ "$on_components" = "$SAVER_SHORT" ] && [ "$on_enabled" = 1 ]; then
    echo "Slate's slideshow is already the screensaver."
    return 0
  fi
  # What was in use before is only recorded if it is not Slate's own: running this twice must
  # not make Slate the screensaver to go back to.
  if ! saver_is_slate "$on_components"; then
    write_saver_record "$on_components" "$on_enabled" || die "the TV would not keep a record of the screensaver in use now (see $SAVER_RECORD),
  so nothing was changed."
  fi
  if ! put_saver_setting screensaver_components "$SAVER_SHORT" || ! put_saver_setting screensaver_enabled 1; then
    echo "The TV did not accept the change. Putting the earlier screensaver back..."
    saver_off quiet || true
    die "Slate's slideshow could not be made the screensaver on this TV."
  fi
  if [ "$on_components" = null ]; then on_components="the TV's default"; fi
  echo "Slate's slideshow is now the screensaver (before: $(show "$on_components"))."
  on_sleep="$(read_saver_setting screensaver_activate_on_sleep)" || exit 1
  if [ "$on_sleep" = 0 ]; then
    echo "Note: this TV is set not to start a screensaver when it has been idle (screensaver_activate_on_sleep"
    echo "is 0). If it never appears, look for a screensaver or ambient mode setting on the TV."
  fi
  echo "Choose what it shows on the TV under Customise > Screensaver. Undo with: scripts/slate.sh screensaver-off"
}

# Puts back the screensaver used before screensaver-on. With "quiet", says nothing if there is
# nothing to do. Fails if what was there before could not be put back.
saver_off() {
  local off_components off_record=0
  off_components="$(read_saver_setting screensaver_components)" || exit 1
  if read_saver_record; then off_record=1; fi
  if saver_is_slate "$off_components"; then
    if [ "$off_record" = 1 ]; then
      if ! put_saver_setting screensaver_components "$SAVED_COMPONENTS" || ! put_saver_setting screensaver_enabled "$SAVED_ENABLED"; then
        echo "The earlier screensaver could not be put back ($(show "$SAVED_COMPONENTS")). The record is kept on the TV; try again."
        return 1
      fi
      if [ "$SAVED_COMPONENTS" = null ]; then
        echo "Screensaver put back to the TV's default."
      else
        echo "Screensaver put back to: $SAVED_COMPONENTS."
      fi
    else
      # Set some other way: Android's own choice is the best there is to go back to.
      if ! put_saver_setting screensaver_components null; then
        echo "Slate's slideshow could not be removed as the screensaver."
        return 1
      fi
      echo "There was no record of an earlier screensaver, so the TV's default is used again."
    fi
  elif [ "$off_record" = 1 ]; then
    echo "The screensaver was changed on the TV since screensaver-on (it is now $(show "$off_components")), so it is left as it is."
  elif [ "${1:-}" != quiet ]; then
    echo "Slate's slideshow is not the screensaver; nothing to do."
  fi
  if [ "$off_record" = 1 ]; then
    dev rm -f "$SAVER_RECORD" >/dev/null 2>&1 || true
    if read_saver_record; then
      echo "Note: the record $SAVER_RECORD could not be removed from the TV."
    fi
  fi
  return 0
}

# The full undo: stock home screen on, guard off, the earlier screensaver back.
restore() {
  local failed=0
  restore_stock || failed=1
  guard_off
  # In a subshell: an unreadable screensaver setting must not stop what comes after a restore
  # (rescue's wipe, uninstall's checks).
  (saver_off quiet) || failed=1
  dev input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  return $failed
}

action="${1:-}"
case "$action" in
  "" | help | -h | --help)
    usage
    ;;

  connect)
    address="${2:-}"
    [ -n "$address" ] || die "usage: scripts/slate.sh connect <tv-ip>"
    case "$address" in
      *:*) ;;
      *) address="$address:5555" ;;
    esac
    out="$("$ADB" connect "$address" 2>&1 || true)"
    show "$out"
    case "$out" in
      *"connected to"*) ;;
      *) die "could not connect. Check the address and that network debugging is on, then accept the prompt on the TV." ;;
    esac
    ;;

  install)
    require_device
    apk="$(find_apk)"
    case "$apk" in
      *app-debug.apk) echo "Note: installing the DEBUG build (no release build is newer). Prefer ./gradlew assembleRelease." ;;
    esac
    if out="$("$ADB" install -r "$apk" 2>&1)"; then
      show "$out"
    else
      show "$out" >&2
      die "Slate could not be installed (see above)."
    fi
    # Pre-compile so the home screen starts quickly. Not essential, so failure is ignored.
    dev cmd package compile -m speed -f "$PKG" >/dev/null 2>&1 || true
    ;;

  update)
    require_device
    command -v curl >/dev/null 2>&1 || die "update needs curl on this computer."
    latest="$(latest_version)" || die "could not find the latest release at
  $RELEASES
  (no connection, or nothing has been published there yet)."
    current=""
    if has "$PKG"; then current="$(installed_version)"; fi
    if [ -n "$current" ] && [ "$current" = "$latest" ]; then
      echo "Slate $current is the latest release. Nothing to do."
      exit 0
    fi

    tmp="$(mktemp -d)" || die "could not make a folder for the download."
    trap 'rm -rf -- "$tmp"' EXIT
    echo "Downloading Slate $latest..."
    fetch "$RELEASES/download/v$latest/$RELEASE_APK" "$tmp/$RELEASE_APK" ||
      die "could not download Slate $latest. Nothing was changed."
    fetch "$RELEASES/download/v$latest/$RELEASE_APK.sha256" "$tmp/$RELEASE_APK.sha256" ||
      die "could not download the checksum of Slate $latest. Nothing was changed."
    want="$(LC_ALL=C sed -n '1s/^\([0-9a-f]\{64\}\)\([^0-9a-f].*\)\{0,1\}$/\1/p' "$tmp/$RELEASE_APK.sha256")"
    got="$(sha256_of "$tmp/$RELEASE_APK")"
    if [ -z "$want" ] || [ "$want" != "$got" ]; then
      die "the download does not match its published checksum. Nothing was installed."
    fi

    if out="$("$ADB" install -r "$tmp/$RELEASE_APK" 2>&1)"; then
      show "$out"
    else
      show "$out" >&2
      case "$out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
          die "the Slate on the TV was signed with a different key than the released one (a copy you
  built yourself?), and Android does not update across keys. Nothing was changed. Either keep
  updating it your way (build, then scripts/slate.sh install), or copy your settings off the TV
  (pull-config), remove Slate (scripts/slate.sh uninstall) and run update again." ;;
        *INSTALL_FAILED_VERSION_DOWNGRADE*)
          die "the Slate on the TV (${current:-unknown version}) is newer than the latest release ($latest).
  Nothing was changed." ;;
      esac
      die "Slate could not be installed (see above)."
    fi
    # Pre-compile so the home screen starts quickly. Not essential, so failure is ignored.
    dev cmd package compile -m speed -f "$PKG" >/dev/null 2>&1 || true
    if [ -n "$current" ]; then
      echo "Slate $latest is installed (was $current). Settings are kept."
    else
      echo "Slate $latest is installed. Next: scripts/slate.sh takeover"
    fi
    ;;

  takeover)
    require_device
    require_slate
    if is_disabled "$PKG"; then die "Slate is installed but disabled on the TV."; fi

    # Never switch the stock home screen off unless Slate demonstrably starts and stays up.
    # "A process exists" is not enough: Android restarts a crashed home screen, so the check is
    # that the same process is still there and that its screen is the one in front.
    if ! can_send_restore; then
      if link_ok; then
        die "this computer's adb could not deliver a command as long as the one that undoes takeover
  (an old adb version?). Update the Android platform-tools. Nothing was changed."
      fi
      die "lost contact with the TV. Nothing was changed."
    fi

    echo "Checking that Slate starts and stays up..."
    uid="$(slate_uid)" || die "the TV did not say which user id Slate has, so it cannot be told whether Slate
  stays up. Nothing was changed."
    dev am start -W -n "$HOME_ACTIVITY" >/dev/null || die "Slate did not start. Nothing was changed."
    sleep 1
    pids_before="$(slate_pids "$uid")" || pids_failed $?
    sleep 6
    pids_after="$(slate_pids "$uid")" || pids_failed $?
    [ -n "$pids_after" ] || die "Slate is not running a few seconds after starting, so it is probably crashing. Nothing was changed."
    [ "$pids_before" = "$pids_after" ] || die "Slate restarted by itself while being watched, so it is probably crashing. Nothing was changed."
    slate_on_screen "$uid" || die "Slate's home screen is not showing a few seconds after starting it (is the TV awake?).
  Nothing was changed."

    # What the Home button opens now, if that is a home screen proper, is remembered on the TV so
    # that restore can go back to exactly that one, whoever it belongs to.
    before="$(current_home)" || die "lost contact with the TV. Nothing was changed."
    previous=""
    if is_home_screen "$before"; then previous="$before"; fi
    if [ -n "$previous" ] && ! is_system_package "${previous%%/*}"; then
      echo "Note: the Home button opens $previous at the moment, which is not part of the system."
      echo "restore will hand the Home button back to it where it can. If you did not choose it as"
      echo "your home screen yourself, remove that app: restore will then not go back to it."
    fi

    echo "Switching the stock home screen off..."
    # As with restore, the script's own last line is what says it ran to the end.
    out="$(dev_script "$TAKEOVER_SCRIPT" "$previous")" || true
    case "$out" in
      *takeover-finished) ;;
      *) die "lost contact with the TV. Run: scripts/slate.sh status   then, if needed: scripts/slate.sh restore" ;;
    esac
    out="$(grep -vx "takeover-finished" <<<"$out" || true)"
    if [ -n "$out" ]; then show "$out"; fi
    case "$out" in
      *"so nothing was changed"*) die "takeover was not completed (see above). Nothing was changed." ;;
    esac

    home="$(current_home)" || die "lost contact with the TV. Run: scripts/slate.sh status   then, if needed: scripts/slate.sh restore"
    case "$home" in
      "$PKG"/*) ;;
      *)
        echo "Android still sends the Home button to: ${home:-nothing}. Undoing."
        # Only the stock home screen is put back; a guard that was already on stays on.
        if restore_stock; then
          case "$out" in
            *"could not record "*)
              die "takeover was not completed: the TV would not keep the record of what gets changed
  (see above; its storage may be full). The Home button is as it was."
              ;;
          esac
          die "takeover did not work on this device, and the Home button is as it was.
  Use the fallback instead: scripts/slate.sh guard-on"
        else
          die "takeover did not work on this device, AND $(restore_complaint)
  Run: scripts/slate.sh status"
        fi
        ;;
    esac
    dev input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    # Said, and waited for, before anything else can fail, so the state of the TV is never in doubt.
    echo "Android now sends the Home button to Slate."
    switched=0
    case "$out" in
      *"  switched off "*) switched=1 ;;
    esac
    # Telling Android which home screen to use is a change to be saved as well.
    if [ "$switched" = 1 ] || [ "${before%%/*}" != "$PKG" ]; then settle; fi
    # Going by what is on the TV now, not by what the step above reported per package. In a
    # subshell: an unanswered question here must not end the script before it has said the rest.
    if (other_home_exists system) >/dev/null 2>&1; then
      echo "A home screen that is part of the system is still switched on (this device would not switch"
      echo "it off, or this script does not know it), so it may take over again, after a restart for"
      echo "instance. For a firmer hold, also run: scripts/slate.sh guard-on"
    fi
    echo
    # In a subshell: takeover stands whether or not the TV still answers for this.
    (status) || echo "(the TV did not answer, so its status is not shown; takeover is in place)"
    echo
    echo "Done. Undo at any time with: scripts/slate.sh restore"
    echo "Do not uninstall Slate without restoring first, or the TV will have no home screen."
    ;;

  restore)
    require_device
    if restore; then
      echo
      status
    else
      echo
      status
      die "$(restore_complaint)"
    fi
    ;;

  rescue)
    require_device
    restored=1
    restore || restored=0
    if has "$PKG"; then
      echo "Wiping Slate's data (settings, wallpaper, artwork, exported settings)..."
      dev pm clear "$PKG" >/dev/null || die "could not wipe Slate's data"
    fi
    echo
    status
    [ "$restored" = 1 ] || die "$(restore_complaint)"
    ;;

  uninstall)
    require_device
    # A restore that reports a problem, or stops, is not the end of it: what matters is the
    # check below. (In a subshell, so that stopping there does not stop this.)
    restore_problem=0
    (restore) || restore_problem=1
    # Removing Slate is only safe if something else will be the home screen afterwards: either
    # Android already sends Home to a home screen that is part of the system, or one such is
    # switched on and will take over. A launcher the owner installed themselves counts only if
    # they say so, because this script cannot tell a launcher from an app that merely claims to
    # be one. And with no other home screen at all, not even saying so is enough.
    home="$(current_home)" || die "lost contact with the TV, so Slate was NOT removed."
    case "$home" in
      "$SETUP_HELPER"/*)
        die "the Home button opens the setup helper, which is not a home screen, so Slate was NOT removed.
  Switch the helper off (adb shell pm disable-user --user 0 $SETUP_HELPER) or choose a home
  screen first, then try again. Run: scripts/slate.sh status"
        ;;
    esac
    if is_home_screen "$home" && is_system_package "${home%%/*}"; then
      echo "The Home button already opens $home."
    elif has "$SETUP_HELPER" && ! is_disabled "$SETUP_HELPER"; then
      # With Slate gone, Android picks the home screen itself, and the setup helper answers the
      # Home button too: it could be what Home opens next.
      die "the Home button does not open a system home screen yet, and the setup helper is switched on,
  which Android could pick instead, so Slate was NOT removed. Run restore again; if Home
  still does not open the system home screen, switch the helper off
  (adb shell pm disable-user --user 0 $SETUP_HELPER), then try again. Run: scripts/slate.sh status"
    elif other_home_exists system; then
      echo "A system home screen is switched on: $FOUND_HOME."
    elif ! other_home_exists any; then
      die "no other home screen is switched on at all, so Slate was NOT removed: the TV would be
  left without one. Run: scripts/slate.sh status"
    elif [ "${2:-}" = "--anyway" ]; then
      echo "Removing Slate although no system home screen is switched on, as asked."
      echo "What is left as a home screen: $FOUND_HOME."
    else
      die "no home screen that is part of the system is switched on, so Slate was NOT removed.
  What would be left as a home screen: $FOUND_HOME.
  If that is what you want, run: scripts/slate.sh uninstall --anyway"
    fi
    if out="$("$ADB" uninstall "$PKG" 2>&1)"; then
      show "$out"
    else
      show "$out" >&2
      die "Slate could not be removed (see above)."
    fi
    if [ "$restore_problem" = 1 ]; then
      echo "Note: the restore before this reported a problem (see above). With Slate removed,"
      echo "scripts/slate.sh restore still works for anything left switched off."
    fi
    ;;

  guard-on)
    require_device
    require_slate
    guard_on
    echo "Home button guard is on."
    ;;

  guard-off)
    require_device
    guard_off
    echo "Home button guard is off."
    ;;

  status)
    require_device
    status
    ;;

  watch-next)
    require_device
    require_slate
    # The same permission Android asks about on the TV when the row is switched on there.
    if out="$(dev pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>&1)"; then
      echo "Slate may now read the TV's watch-next list."
      echo "On the TV choose Customise > Home layout > Continue watching to show the row."
    else
      show "$out" >&2
      die "the TV did not allow it (see above). Try on the TV: Customise > Home layout > Continue watching."
    fi
    ;;

  wallpaper)
    file="${2:-}"
    [ -n "$file" ] || die "usage: scripts/slate.sh wallpaper <image.jpg|jpeg|png>"
    [ -f "$file" ] || die "no such file: $file"
    name="${file##*/}"
    case "$name" in
      *.*) extension="$(printf '%s' "${name##*.}" | tr '[:upper:]' '[:lower:]')" ;;
      *) extension="" ;;
    esac
    case "$extension" in
      jpg | jpeg | png) ;;
      *) die "unsupported image type. Use .jpg, .jpeg or .png" ;;
    esac
    # A name starting with a dash would otherwise be read as an option.
    case "$file" in -*) file="./$file" ;; esac
    require_device
    require_slate
    require_app_folder
    # Upload under a temporary name first, so a failed upload leaves the old wallpaper alone.
    refuse_folder "$FILES/wallpaper.$extension"
    refuse_folder "$FILES/wallpaper.upload"
    push "$file" "$FILES/wallpaper.upload"
    dev mv "$FILES/wallpaper.upload" "$FILES/wallpaper.$extension" >/dev/null 2>&1 || die "the upload could not be put in place on the TV"
    # An upload keeps the date the picture had on this computer. Stamp it with "now", so Slate
    # can tell it is newer than whatever it was showing before.
    dev touch "$FILES/wallpaper.$extension" >/dev/null 2>&1 || echo "Note: could not update the file's date; if the old wallpaper stays, pick 'Reset everything' or re-upload a renamed copy."
    for other in jpg jpeg png webp; do
      if [ "$other" != "$extension" ]; then dev rm -f "$FILES/wallpaper.$other" >/dev/null 2>&1 || true; fi
    done
    echo "Wallpaper uploaded. On the TV choose Customise > Appearance > Background > Wallpaper."
    echo "If it is already selected, leave the home screen and come back to see the new image."
    ;;

  icon)
    package="${2:-}"
    file="${3:-}"
    [ -n "$package" ] && [ -n "$file" ] || die "usage: scripts/slate.sh icon <package.name> <image.png>"
    [[ "$package" =~ ^[A-Za-z0-9_.]+$ ]] || die "that does not look like a package name: $package"
    [ -f "$file" ] || die "no such file: $file"
    case "$file" in
      *.png | *.PNG) ;;
      *) die "the image must be a .png file" ;;
    esac
    case "$file" in -*) file="./$file" ;; esac
    require_device
    require_slate
    require_app_folder
    dev mkdir -p "$FILES/icons" >/dev/null 2>&1 || die "the icons folder could not be created on the TV"
    refuse_folder "$FILES/icons/$package.png"
    push "$file" "$FILES/icons/$package.png"
    echo "Artwork uploaded. Leave the home screen and come back to see it."
    ;;

  photos)
    case "${2:-}" in
      add)
        folder="${3:-}"
        [ -n "$folder" ] || die "usage: scripts/slate.sh photos add <folder>"
        [ -d "$folder" ] || die "no such folder: $folder"
        case "$folder" in -*) folder="./$folder" ;; esac
        photo_files=()
        while IFS= read -r -d '' photo; do photo_files+=("$photo"); done < <(
          find "$folder" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) -print0 | sort -z
        )
        [ "${#photo_files[@]}" -gt 0 ] || die "no .jpg, .jpeg or .png files in $folder"
        require_device
        require_slate
        require_app_folder
        dev mkdir -p "$FILES/photos" >/dev/null 2>&1 || die "the photos folder could not be created on the TV"
        dev test -d "$FILES/photos" || die "the photos folder could not be created on the TV"
        uploaded=0
        skipped=0
        # The names given so far, one per line (no associative arrays: macOS still has bash 3).
        photo_names=$'\n'
        for photo in "${photo_files[@]}"; do
          case "$photo" in -*) photo="./$photo" ;; esac
          size="$(wc -c <"$photo" | tr -d ' ')" || size=0
          base="${photo##*/}"
          if [ "$size" -le 0 ] || [ "$size" -gt "$MAX_PHOTO_BYTES" ]; then
            echo "Skipped (empty or over 16 MB): $(show "$base")"
            skipped=$((skipped + 1))
            continue
          fi
          # A plain name on the TV: letters, digits, dot, dash and underscore only, the type in
          # lower case, nothing that starts with a dot or a dash.
          extension="$(printf '%s' "${base##*.}" | tr '[:upper:]' '[:lower:]')"
          stem="$(printf '%s' "${base%.*}" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-80)"
          case "$stem" in "" | .* | -*) stem="photo$stem" ;; esac
          target="$stem.$extension"
          number=1
          # Compared in lower case: shared storage on the TV does not tell Photo.jpg from photo.jpg.
          while [[ "$photo_names" == *$'\n'"$(printf '%s' "$target" | tr '[:upper:]' '[:lower:]')"$'\n'* ]]; do
            number=$((number + 1))
            target="$stem-$number.$extension"
          done
          photo_names+="$(printf '%s' "$target" | tr '[:upper:]' '[:lower:]')"$'\n'
          refuse_folder "$FILES/photos/$target"
          refuse_folder "$FILES/photos/upload.tmp"
          # Under a name Slate does not show, until the upload is complete.
          if ! out="$("$ADB" push "$photo" "$FILES/photos/upload.tmp" 2>&1)"; then
            show "$out" >&2
            die "the upload of $(show "$base") failed. $uploaded photo(s) were uploaded before it."
          fi
          dev mv "$FILES/photos/upload.tmp" "$FILES/photos/$target" >/dev/null 2>&1 ||
            die "$(show "$base") could not be put in place on the TV. $uploaded photo(s) were uploaded before it."
          uploaded=$((uploaded + 1))
        done
        if [ "$skipped" -gt 0 ]; then
          echo "$uploaded photo(s) uploaded, $skipped skipped. A photo with the same name as one uploaded before replaces it."
        else
          echo "$uploaded photo(s) uploaded. A photo with the same name as one uploaded before replaces it."
        fi
        echo "Choose Customise > Screensaver on the TV to pick what the screensaver shows."
        ;;
      clear)
        require_device
        require_slate
        require_app_folder
        dev rm -rf "$FILES/photos" >/dev/null 2>&1 || true
        # Only a clear "not there" from the TV counts; no answer is not success.
        gone="$("$ADB" shell "test -e $FILES/photos; echo photos-\$?" </dev/null 2>/dev/null || true)"
        [ "$gone" = photos-1 ] || die "the photos could not be removed from the TV (or it stopped answering). Check with: adb shell ls $FILES/photos"
        echo "Photos uploaded from a computer are removed. Photos added on the TV itself are removed under"
        echo "Customise > Screensaver."
        ;;
      *) die "usage: scripts/slate.sh photos add <folder>   or   scripts/slate.sh photos clear" ;;
    esac
    ;;

  screensaver-on)
    require_device
    require_slate
    saver_installed || die "the Slate on the TV has no screensaver yet. Install the new version first: scripts/slate.sh install"
    saver_on
    ;;

  screensaver-off)
    require_device
    saver_off || die "the earlier screensaver was not put back (see above)."
    ;;

  pull-config)
    require_device
    target="${2:-slate-config.json}"
    case "$target" in -*) target="./$target" ;; esac
    dev test -f "$FILES/slate-config.json" || die "there are no exported settings on the TV. On the TV choose Customise > System > Export settings first."
    if out="$("$ADB" pull "$FILES/slate-config.json" "$target" 2>&1)"; then
      show "$out"
    else
      show "$out" >&2
      die "the download failed"
    fi
    ;;

  push-config)
    file="${2:-slate-config.json}"
    [ -f "$file" ] || die "no such file: $file"
    case "$file" in -*) file="./$file" ;; esac
    require_device
    require_slate
    require_app_folder
    refuse_folder "$FILES/slate-config.json"
    push "$file" "$FILES/slate-config.json"
    echo "Settings uploaded. On the TV choose Customise > System > Import settings."
    ;;

  *)
    usage >&2
    exit 2
    ;;
esac
