#!/usr/bin/env bash
# deploy.sh から nix-shell の中で呼ばれる。単独では実行しない
set -euo pipefail
PKG=jp.kamome.btkeyboard
adb_bt() { adb shell settings get global bluetooth_on | tr -d '\r'; }
bt_wait() { for _ in $(seq 1 20); do [ "$(adb_bt)" = "$1" ] && return 0; adb shell sleep 1; done; return 1; }

gradle --no-daemon -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant $PKG android.permission.BLUETOOTH_ADVERTISE || true
adb shell am force-stop $PKG
# 前の HID 登録が残る問題はアプリ側で自動復旧する。残りが疑わしいときだけ BT_RESET=1 で Bluetooth を入れ直す
if [ "${BT_RESET:-0}" = 1 ]; then
  # svc は成功しても終了コード 137 を返すので無視する
  adb shell svc bluetooth disable || true; bt_wait 0; adb shell sleep 2
  adb shell svc bluetooth enable || true; bt_wait 1; adb shell sleep 4
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/.MainActivity
adb shell sleep 3
if adb shell dumpsys window | grep mCurrentFocus | grep -q $PKG; then
  echo 'OK: アプリが前面にあります'
else
  echo 'ERROR: アプリが前面にありません（端末がロック中かもしれません）'; exit 1
fi
