#!/usr/bin/env bash
# pc-input-test.sh から nix-shell の中で呼ばれる。単独では実行しない
set -euo pipefail
PKG=jp.kamome.btkeyboard
LABELS=$TMPDIR/labels.tsv

front() {
  adb shell dumpsys window | grep mFocusedApp | grep -q "$PKG" || { echo "ERROR: 前面が $PKG ではありません"; exit 1; }
}
# 画面上の文字と中心座標を LABELS に書き出す（ラベル<TAB>x<TAB>y）
dump() {
  front
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | sed -E 's/text="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1\t\2\t\3\t\4\t\5/' \
    | awk -F'\t' '{printf "%s\t%d\t%d\t%d\n", $1, ($2+$4)/2, ($3+$5)/2, $4-$2}' > "$LABELS"
}
# このデバイスでは、画面の右端に近いキーの座標が画面情報から正しく取れない（幅が 0〜2px になる。細い文字（l など）と区別するため、5px 未満だけを対象にする）。
# その場合は、同じ行の別のキーの高さを借りて、右端付近（x=2240）を押す
declare -A RIGHT_REF=( [BS]=1 [Enter]=q )
tap_xy() {  # $1=ラベル $2=部分一致なら 1
  local line x y w ref
  if [ "${2:-0}" = 1 ]; then line=$(awk -F'\t' -v l="$1" 'index($1,l){print; exit}' "$LABELS")
  else line=$(awk -F'\t' -v l="$1" '$1==l{print; exit}' "$LABELS"); fi
  [ -n "$line" ] || { echo "ERROR: 「$1」がありません"; exit 1; }
  IFS=$'\t' read -r _ x y w <<<"$line"
  if [ "$w" -lt 5 ]; then
    ref=${RIGHT_REF[$1]:-}
    [ -n "$ref" ] || { echo "ERROR: 「$1」の座標が取れません"; exit 1; }
    y=$(awk -F'\t' -v l="$ref" '$1==l{print $3; exit}' "$LABELS"); x=2240
  fi
  adb shell input tap "$x" "$y"; adb shell sleep 0.35
}
press()     { tap_xy "$1" 0; }
press_sub() { tap_xy "$1" 1; }

# 接続設定の画面にいるなら、入力画面へ進む
open_input() {
  dump
  if awk -F'\t' 'index($1,"入力画面へ"){f=1} END{exit !f}' "$LABELS"; then
    press_sub "入力画面へ"; adb shell sleep 2; dump
  fi
  # メディア画面・トラックパッド画面から始まった場合は、キーボード画面へ戻る
  if ! awk -F'\t' '$1=="F1"{f=1} END{exit !f}' "$LABELS" && awk -F'\t' '$1=="キーボード"{f=1} END{exit !f}' "$LABELS"; then
    press "キーボード"; adb shell sleep 1; dump
  fi
  awk -F'\t' '$1=="F1"{f=1} END{exit !f}' "$LABELS" || { echo "ERROR: キーボード画面を開けませんでした（レイアウトが標準でない可能性）"; exit 1; }
}

mode=${1:-text}
open_input
case "$mode" in
  text)
    echo "送信: hello world ⏎ Shift+a b BS   （PC には「hello world」と改行、続けて「A」が出るはずです）"
    for k in h e l l o; do press "$k"; done
    press "Space"
    for k in w o r l d; do press "$k"; done
    press "Enter"
    press "Shift"; press "a"       # Shift を押してから a → 大文字の A
    press "b"; press "BS"           # b を入力して、BS で消す
    ;;
  media)
    echo "送信: 音量− → 音量＋"
    press "メディア"; adb shell sleep 1; dump
    press_sub "音量−"; adb shell sleep 1
    press_sub "音量＋"; adb shell sleep 1
    press "キーボード"; adb shell sleep 1
    ;;
  mouse)
    echo "送信: トラックパッドで右→下→左→上に動かして、最後に 1 回クリック"
    press "パッド"; adb shell sleep 1
    front
    # パッドの中央付近を起点にする（横向き 2400x1080 の想定）
    x=1000; y=450
    adb shell input swipe $x $y $((x+300)) $y 500; adb shell sleep 0.3
    adb shell input swipe $((x+300)) $y $((x+300)) $((y+250)) 500; adb shell sleep 0.3
    adb shell input swipe $((x+300)) $((y+250)) $x $((y+250)) 500; adb shell sleep 0.3
    adb shell input swipe $x $((y+250)) $x $y 500; adb shell sleep 0.3
    adb shell input tap $x $y; adb shell sleep 0.5
    dump; press "キーボード"; adb shell sleep 1
    ;;
  *) echo "使い方: pc-input-test.sh text|media|mouse"; exit 2 ;;
esac
echo "完了"
