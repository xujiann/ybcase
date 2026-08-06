# ybcase 数据库备份（挂 Windows 任务计划每日执行；保留最近 30 份）
# 用法：powershell -File tools/db-backup.ps1 [-OutDir D:\backup\ybcase]
param(
    [string]$OutDir = "$PSScriptRoot\..\backup"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$file = Join-Path $OutDir "ybcase-$stamp.dump"
# 开发环境 PG 在 WSL 内；生产环境替换为本机 pg_dump 路径
wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_dump -U hip -h 127.0.0.1 -Fc ybcase" > $file
if ((Get-Item $file).Length -lt 1024) { throw "备份文件异常过小：$file" }
Write-Output "备份完成：$file （$([math]::Round((Get-Item $file).Length/1KB)) KB）"
# 滚动清理
Get-ChildItem $OutDir -Filter 'ybcase-*.dump' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 30 | Remove-Item -Force
# 恢复演练：wsl -d Ubuntu -e bash -c "sudo -u postgres createdb -O hip ybcase_restore" ；
#           type <file> | wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_restore -U hip -h 127.0.0.1 -d ybcase_restore"
