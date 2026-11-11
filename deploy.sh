#!/usr/bin/env bash
# ビルド → 接続中の端末へインストール → 権限付与 → 起動して前面を確認する
# 使い方: ./deploy.sh        （nix-shell の外から実行する）。Bluetooth も入れ直すなら BT_RESET=1 ./deploy.sh
cd "$(dirname "$0")"
export GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home TMPDIR=$PWD/.tmp
exec nix-shell --run "bash $PWD/deploy-inner.sh"
