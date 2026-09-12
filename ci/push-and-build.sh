#!/bin/sh
# ci/push-and-build.sh
#
# 把源码树推送到你自己的 GitHub 仓库；推送后 Actions 会自动编译
# debug + release 并上传 APK，无需任何手动构建步骤。
#
# 用法:
#   ./ci/push-and-build.sh <你的GitHub用户名> <仓库名>
#
# 鉴权二选一（都在你自己的设备上完成，凭据不出本机）:
#   1. git 已登录（credential manager 或 SSH），直接运行;
#   2. 用环境变量传一个仅授权该仓库的 token，脚本推完后会自动
#      擦掉远程 URL 里的凭据:
#        GH_PUSH_TOKEN=你的token ./ci/push-and-build.sh <用户名> <仓库名>
#
# 仓库需先在 GitHub 网页创建好（建议 Private，不要勾选 README）。
set -eu

USER_NAME="${1:?用法: ci/push-and-build.sh <GitHub用户名> <仓库名>}"
REPO_NAME="${2:?用法: ci/push-and-build.sh <GitHub用户名> <仓库名>}"

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(dirname "$HERE")

[ -f "$ROOT/gradlew" ] || { echo "错误: 请把本脚本放在源码树内的 ci/ 目录下再运行" >&2; exit 1; }
[ -f "$ROOT/.github/workflows/build.yml" ] || { echo "错误: 缺少 .github/workflows/build.yml" >&2; exit 1; }

cd "$ROOT"

git init -q
git add .
git -c user.name="Eta Codex" -c user.email="eta-codex@users.noreply.github.com" \
    commit -q -m "Eta Codex subscription integration" || true
git branch -M main

BASE_URL="https://github.com/${USER_NAME}/${REPO_NAME}.git"
git remote remove origin 2>/dev/null || true

if [ -n "${GH_PUSH_TOKEN:-}" ]; then
    git remote add origin "https://x-access-token:${GH_PUSH_TOKEN}@github.com/${USER_NAME}/${REPO_NAME}.git"
    git push -u origin main
    # 用后即擦：不在本地 .git/config 里留下凭据。
    git remote set-url origin "$BASE_URL"
else
    git remote add origin "$BASE_URL"
    git push -u origin main
fi

echo
echo "推送完成。构建已自动开始，进度见:"
echo "  https://github.com/${USER_NAME}/${REPO_NAME}/actions"
echo "构建结束后进入本次 run -> Summary -> Artifacts，下载 eta-codex-release-apk。"
