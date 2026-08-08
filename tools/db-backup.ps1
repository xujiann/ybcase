# ybcase 数据库备份（挂 Windows 任务计划每日执行；保留最近 30 份）
# dump 在 WSL 内完成后经 \\wsl.localhost 拷出（二进制不得经 PowerShell 管道）
param(
    [string]$OutDir = "$PSScriptRoot\..\backup"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_dump -U hip -h 127.0.0.1 -Fc -f /tmp/ybcase-backup.dump ybcase"
# 原生命令的失败不会触发 $ErrorActionPreference，必须显式判退出码，
# 否则 dump 中途失败仍会写出一个 >10KB 的截断文件，滚动清理再把最后一份好备份删掉
if ($LASTEXITCODE -ne 0) { throw "pg_dump 失败（退出码 $LASTEXITCODE），本次不产出备份文件" }
$file = Join-Path $OutDir "ybcase-$stamp.dump"
Copy-Item "\\wsl.localhost\Ubuntu\tmp\ybcase-backup.dump" $file
# 可读性校验：pg_restore --list 能列出目录才算完整的自定义格式转储
wsl -d Ubuntu -e bash -c "pg_restore --list /tmp/ybcase-backup.dump > /dev/null"
$listOk = $LASTEXITCODE
wsl -d Ubuntu -e bash -c "rm -f /tmp/ybcase-backup.dump"
if ($listOk -ne 0) { Remove-Item $file -Force; throw "备份文件损坏（pg_restore --list 失败），已删除：$file" }
if ((Get-Item $file).Length -lt 10kb) { Remove-Item $file -Force; throw "备份文件异常过小，已删除：$file" }
Write-Output ("备份完成：" + $file + " (" + [math]::Round((Get-Item $file).Length/1KB) + " KB)")
# 滚动清理
Get-ChildItem $OutDir -Filter 'ybcase-*.dump' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 30 | Remove-Item -Force
# 恢复演练见 tools/upgrade-drill.ps1（含带数据升级校验）
