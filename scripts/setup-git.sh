#!/bin/sh
# =====================================================================
#  启用仓库内置的 Git 钩子与提交模板（Linux / macOS / Git Bash）
#
#  用法：
#     sh scripts/setup-git.sh
# =====================================================================
set -e

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "✖ 当前目录不是 Git 仓库，请先 git init 或进入仓库目录。"
    exit 1
}

cd "$repo_root"

git config core.hooksPath .githooks
git config commit.template .gitmessage

# 确保钩子可执行（Windows 检出时可能丢失该位）
chmod +x .githooks/* 2>/dev/null || true

echo "✔ 已启用版本化 Git 钩子：core.hooksPath = .githooks"
echo "✔ 已启用提交信息模板：commit.template = .gitmessage"
echo ""
echo "当前配置："
echo "  core.hooksPath   = $(git config --get core.hooksPath)"
echo "  commit.template  = $(git config --get commit.template)"
echo ""
echo "校验效果：git commit -m \"update\" 会被拒绝并提示正确格式。"
echo "规范详见：docs/GIT_CONVENTION.md"
