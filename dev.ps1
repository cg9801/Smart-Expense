# dev.ps1 —— 一键启动开发环境（两个前台窗口）
# 为什么用前台窗口：AI 会话里的后台 job 会被环境周期性回收（起来几分钟就 exit 1），
# 这个脚本把服务开在你自己的终端窗口里，不会被回收，Ctrl+C 才停。
# 用法（在仓库根目录）：powershell -ExecutionPolicy Bypass -File .\dev.ps1

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = 'C:\Program Files\Java\jdk-25.0.4.1'

if (-not (Test-Path "$root\Smart-Expense\mvnw.cmd")) {
    Write-Host "未找到 Smart-Expense 工程，请在仓库根目录运行本脚本" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path "$root\frontend\package.json")) {
    Write-Host "未找到 frontend 工程，请在仓库根目录运行本脚本" -ForegroundColor Red
    exit 1
}

Write-Host "启动后端（8080）与前端（5173）两个窗口..." -ForegroundColor Cyan
Write-Host "提示：首次启动需等待 Maven / Vite 就绪；Ctrl+C 可停止。" -ForegroundColor DarkGray

# 后端：新窗口，前台运行
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\Smart-Expense'; `$env:JAVA_HOME='$javaHome'; .\mvnw.cmd spring-boot:run"

# 前端：新窗口，前台运行（依赖已安装；首次可先 pnpm install）
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\frontend'; pnpm dev"

Write-Host "已启动。浏览器打开 http://localhost:5173（admin/123456 可进管理后台）" -ForegroundColor Green
