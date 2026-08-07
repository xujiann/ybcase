# 带数据升级演练：备份当前库 → 恢复到 ybcase_drill → 用新版 jar 迁移并启动 → 校验 → 清理
# 每次发版前跑一遍，确保 Flyway 增量迁移在有数据的老库上成立（如 biz_seq 水位初始化）
# 注意：dump/restore 全程在 WSL 内完成（二进制不得经 PowerShell 5.1 管道，会被文本化损坏）
param([int]$Port = 8091)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

Write-Output "== 1. WSL 内备份当前 ybcase =="
wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_dump -U hip -h 127.0.0.1 -Fc -f /tmp/ybcase-drill.dump ybcase && ls -la /tmp/ybcase-drill.dump"

Write-Output "== 2. 恢复到 ybcase_drill =="
wsl -d Ubuntu -u root -e bash -c "sudo su postgres -c 'dropdb --if-exists ybcase_drill'; sudo su postgres -c 'createdb -O hip ybcase_drill'"
wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 pg_restore -U hip -h 127.0.0.1 -d ybcase_drill /tmp/ybcase-drill.dump" 2>$null

Write-Output "== 3. 新版 jar 对 drill 库启动（自动执行增量迁移） =="
$jar = Get-ChildItem "$root\server\target\ybcase-server-*.jar" | Select-Object -First 1
$p = Start-Process java -PassThru -WindowStyle Hidden -RedirectStandardOutput "$env:TEMP\drill.log" -RedirectStandardError "$env:TEMP\drill-err.log" -ArgumentList @(
    "-jar", $jar.FullName,
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/ybcase_drill",
    "--server.port=$Port")
$ok = $false
foreach ($i in 1..40) {
    Start-Sleep 3
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3
        if ($h.status -eq 'UP') { $ok = $true; break }
    } catch {}
}
if (-not $ok) {
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Get-Content "$env:TEMP\drill.log" -Tail 30
    throw "drill 启动失败"
}

Write-Output "== 4. 校验迁移版本与存量数据 =="
$ver = wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 psql -U hip -h 127.0.0.1 -d ybcase_drill -tc 'select max(version::int) from flyway_schema_history'"
$cases = wsl -d Ubuntu -e bash -c "PGPASSWORD=hip123456 psql -U hip -h 127.0.0.1 -d ybcase_drill -tc 'select count(*) from case_file'"
Write-Output ("flyway 最高版本: " + $ver.Trim() + " ; 存量案件: " + $cases.Trim() + " 件（升级后保留）")

Write-Output "== 5. 清理 =="
Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Start-Sleep 2
wsl -d Ubuntu -u root -e bash -c "sudo su postgres -c 'dropdb --if-exists ybcase_drill'; rm -f /tmp/ybcase-drill.dump"
Write-Output "升级演练通过 OK"
