#!/usr/bin/env bash
# 実際の PC に入力が届くかを確認する。RP03 の画面を adb でタップして、決まった入力を PC へ送る
# 前提: 端末と PC が Bluetooth で接続済み（アプリの状態が「接続中」）で、PC 側のテキスト入力欄にカーソルがあること
# 使い方: tools/pc-input-test.sh text    英数字・Shift・Enter・BS を送る
#         tools/pc-input-test.sh media   音量 −/＋ を送る（PC の音量が一瞬変わる）
#         tools/pc-input-test.sh mouse   トラックパッドで四角を描いてクリックする
cd "$(dirname "$0")/.."
export ANDROID_USER_HOME=$PWD/.android-home TMPDIR=$PWD/.tmp
exec nix-shell --run "bash $PWD/tools/pc-input-test-inner.sh ${1:-text}"
