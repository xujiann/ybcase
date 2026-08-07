# ybcase 数据库备份（挂 Windows 任务计划每日执行；保留最近 30 份）
# dump 在 WSL 内完成后经 \\wsl.localhost 拷出（二进制不得经 PowerShell 管道）
param(
    [string]$OutDir = "$PSScriptRoot\..\backup"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_dump -U hip -h 127.0.0.1 -Fc -f /tmp/ybcase-backup.dump ybcase"
$file = Join-Path $OutDir "ybcase-$stamp.dump"
Copy-Item "\\wsl.localhost\Ubuntu\tmp\ybcase-backup.dump" $file
wsl -d Ubuntu -e bash -c "rm -f /tmp/ybcase-backup.dump"
if ((Get-Item $file).Length -lt 10kb) { throw "备份文件异常过小：$file" }
Write-Output ("备份完成：" + $file + " (" + [math]::Round((Get-Item $file).Length/1KB) + " KB)")
# 滚动清理
Get-ChildItem $OutDir -Filter 'ybcase-*.dump' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 30 | Remove-Item -Force
# 恢复演练见 tools/upgrade-drill.ps1（含带数据升级校验）
