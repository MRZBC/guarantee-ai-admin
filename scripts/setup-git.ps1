# =====================================================================
#  启用仓库内置的 Git 钩子与提交模板（Windows / PowerShell）
#
#  用法：
#     pwsh -File scripts/setup-git.ps1
#  或：
#     powershell -ExecutionPolicy Bypass -File scripts/setup-git.ps1
# =====================================================================

$ErrorActionPreference = 'Stop'

$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Host "✖ 当前目录不是 Git 仓库，请先 git init 或进入仓库目录。" -ForegroundColor Red
    exit 1
}

Push-Location $repoRoot
try {
    git config core.hooksPath .githooks
    git config commit.template .gitmessage

    Write-Host "✔ 已启用版本化 Git 钩子：core.hooksPath = .githooks"
    Write-Host "✔ 已启用提交信息模板：commit.template = .gitmessage"
    Write-Host ""
    Write-Host "当前配置："
    Write-Host ("  core.hooksPath   = " + (git config --get core.hooksPath))
    Write-Host ("  commit.template  = " + (git config --get commit.template))
    Write-Host ""
    Write-Host "校验效果：git commit -m `"update`" 会被拒绝并提示正确格式。"
    Write-Host "规范详见：docs/GIT_CONVENTION.md"
}
finally {
    Pop-Location
}
