# 构建前端并复制到 Spring Boot static/，用于单端口（8080）演示
# 用法：pwsh -File scripts/copy-frontend.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
$static = Join-Path $root 'Smart-Expense\src\main\resources\static'

Push-Location $frontend
try {
    Write-Host '==> pnpm build' -ForegroundColor Cyan
    pnpm build
    if ($LASTEXITCODE -ne 0) { throw "vite build 失败" }
} finally {
    Pop-Location
}

Write-Host '==> 复制 dist -> static/' -ForegroundColor Cyan
New-Item -ItemType Directory -Path $static -Force | Out-Null
Copy-Item -Path (Join-Path $frontend 'dist\*') -Destination $static -Recurse -Force

Write-Host '==> 完成。重启后端后访问 http://localhost:8080/' -ForegroundColor Green
Write-Host '    （src/main/resources/static/index.html 与 assets/ 已在 .gitignore 中）'
