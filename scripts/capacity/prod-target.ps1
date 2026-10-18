# run-step.ps1 -Prod가 dot-source하는 운영 준비 함수.
# 운영 호스트에 SSH로 들어가 지표 터널, JWT secret, 회원 ID, 실행 전 점검을 스스로 준비한다.
# 비밀값(JWT secret, DB 비밀번호)은 화면·파일·명령행에 남기지 않는다.
# - JWT secret은 이 PC 프로세스의 메모리에만 두고 토큰 생성에 쓴다.
# - DB 비밀번호는 운영 호스트 안에서 ticket-be 환경 변수 -> psql 컨테이너로 파이프로만 넘긴다(서버 밖으로 나오지 않는다).
# 호출하는 쪽 변수를 읽는다: $SshCommand, $SshOptions(Initialize-SshSession), $ProdHost, $runDir.
# 메시지는 영어로 쓴다(Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트의 한글 문자열을 깨뜨린다).

$script:prodTunnel = $null

# 원격 bash 스크립트를 base64로 보내 실행한다. 따옴표·CRLF가 Windows 명령행을 거치며 깨지지 않게 한다.
# 스크립트 본문에는 비밀값이 없다. 비밀값은 원격에서 읽어 원격에서만 쓴다.
function Invoke-ProdBash([string]$body) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($body -replace "`r`n", "`n"))
    $out = & $SshCommand @SshOptions $ProdHost ("echo " + [Convert]::ToBase64String($bytes) + " | base64 -d | bash")
    if ($LASTEXITCODE -ne 0) { Fail "remote command on $ProdHost failed (exit $LASTEXITCODE)" }
    @($out)
}

# ticket-be의 SPRING_DATASOURCE_*로 RDS에 psql을 띄우는 bash 함수(psqlq "SQL"). 비밀번호는 env-file로 stdin에서만 읽힌다.
$prodPsqlPrelude = @'
set -euo pipefail
url=$(sudo docker exec ticket-be printenv SPRING_DATASOURCE_URL)
dbuser=$(sudo docker exec ticket-be printenv SPRING_DATASOURCE_USERNAME)
rest=${url#jdbc:postgresql://}
hostport=${rest%%/*}
dbq=${rest#*/}
db=${dbq%%\?*}
dbhost=${hostport%%:*}
dbport=5432
case $hostport in *:*) dbport=${hostport##*:} ;; esac
psqlq() {
  sudo docker exec ticket-be printenv SPRING_DATASOURCE_PASSWORD | sed 's/^/PGPASSWORD=/' |
    sudo docker run --rm --env-file /dev/stdin postgres:18-alpine \
      psql "host=$dbhost port=$dbport dbname=$db user=$dbuser sslmode=require" -At -F , -c "$1"
}
'@

# 실행 전 점검. 운영 Core·DB는 사양이 낮아 점검이 측정을 흔들지 않게 최소로 한다(사용자 결정 2026-10-19, capacity-log P-020).
# - 실행마다: Core 이미지(commit)·컨테이너 IP·free -m만 읽는다. DB에는 묻지 않는다(psql 컨테이너도 띄우지 않는다).
# - -FindPerformance(-PerformanceId를 안 줬을 때): 다음 대형 회차 = 마지막 부하 주문의 회차 + 1.
#   주문을 PK 역순으로 읽다가 첫 부하 주문에서 멈춘다. 예전처럼 "주문이 없는 회차"를 NOT IN으로 찾으면
#   orders에 performance_id로 시작하는 인덱스가 없어 실행마다 주문 500만 건을 다 읽었다.
# - -Full(-CheckOnly): DB 규모는 통계 추정치(pg_class.reltuples)로 남긴다. count(*)는 표 전체를 읽는다.
#   앞단 설정(nginx·compose)과 docker stats도 여기서만 남긴다. 실행 사이에 바뀌지 않고, 컨테이너 메모리는 Datadog에 있다.
function Get-ProdPreflight([switch]$Full, [switch]$FindPerformance) {
    # here-string은 끝 줄바꿈이 없으므로 이어 붙일 때 줄바꿈을 넣는다.
    $body = ""
    if ($Full -or $FindPerformance) { $body += $prodPsqlPrelude + "`n" }
    $body += @'
image=$(sudo docker inspect -f '{{.Config.Image}}' ticket-be)
coreip=$(sudo docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' ticket-be | awk '{print $1}')
echo "image=$image"
echo "coreIp=$coreip"
'@ + "`n"
    if ($Full) {
        $body += @'
echo "db=$(psqlq "select (select reltuples::bigint from pg_class where oid = 'members'::regclass), (select reltuples::bigint from pg_class where oid = 'orders'::regclass), current_setting('max_connections'), (select count(*) from performances where id between 920000001 and 929999999)")"
'@ + "`n"
    }
    if ($FindPerformance) {
        $body += @'
echo "next=$(psqlq "select min(id) from performances where id between 920000001 and 929999999 and id > coalesce((select performance_id from orders where performance_id between 920000001 and 929999999 order by id desc limit 1), 0)")"
'@ + "`n"
    }
    $body += @'
echo '---conditions---'
echo '# ticket-be image'; echo "$image"
echo '# free -m'; free -m
'@ + "`n"
    if ($Full) {
        $body += @'
echo '# nginx'; sudo docker exec ticket-nginx nginx -T 2>/dev/null | grep -nE 'worker_processes|worker_rlimit_nofile|worker_connections|limit_req|limit_conn|gzip' || true
echo '# compose'; grep -nE 'JAVA_TOOL_OPTIONS|Xmx|mem_limit|cpus|HIKARI|TOMCAT' /home/ubuntu/docker-compose.yml || true
echo '# docker stats'; sudo docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
'@
    }
    $lines = Invoke-ProdBash $body
    $marker = [Array]::IndexOf($lines, '---conditions---')
    if ($marker -lt 0) { Fail "unexpected preflight output from $ProdHost" }
    $kv = @{}
    foreach ($line in $lines[0..($marker - 1)]) { if ($line -match '^(\w+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
    $info = [ordered]@{
        Image = $kv.image; CoreIp = $kv.coreIp; Commit = ($kv.image -split ':')[-1]
        MembersEstimate = $null; OrdersEstimate = $null; MaxConnections = $null; LargePerformances = $null; NextPerformance = 0
        Conditions = ($lines[($marker + 1)..($lines.Count - 1)] -join [Environment]::NewLine)
    }
    if ($Full) {
        $db = $kv.db -split ','
        if ($db.Count -lt 4) { Fail "could not read production database size: $($kv.db)" }
        $info.MembersEstimate = [long]$db[0]; $info.OrdersEstimate = [long]$db[1]; $info.MaxConnections = [int]$db[2]; $info.LargePerformances = [int]$db[3]
    }
    if ($FindPerformance -and $kv.next) { $info.NextPerformance = [long]$kv.next }
    [pscustomobject]$info
}

# 부하 회원 ID를 실제 DB 순서대로 받는다. 파일이 없을 때만 부른다.
function Save-ProdMemberIds([string]$path, [int]$count) {
    $ids = Invoke-ProdBash ($prodPsqlPrelude + "`npsqlq ""select id from members where deleted_at is null and email like 'loadtest%@test.com' and role = 'MEMBER' order by id limit $count""")
    $ids = @($ids | Where-Object { $_ -match '^\d+$' })
    if ($ids.Count -lt $count) { Fail "production has only $($ids.Count) load-test members, need $count" }
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    [IO.File]::WriteAllText($path, ($ids -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}

# JWT secret을 ticket-be에서 읽어 메모리로만 돌려준다.
function Get-ProdJwtSecret {
    $value = & $SshCommand @SshOptions $ProdHost "sudo docker exec ticket-be printenv JWT_SECRET"
    if ($LASTEXITCODE -ne 0 -or -not $value) { Fail "could not read JWT_SECRET from ticket-be on $ProdHost" }
    "$value".Trim()
}

# ticket-be 8080으로 가는 SSH 터널을 빈 로컬 포트에 연다. 끊김을 알아차리게 ServerAlive를 켠다(SshOptions에 있음).
function Start-ProdTunnel([string]$coreIp) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    # Start-Process는 인수를 따옴표 없이 공백으로 잇는다. 공백이 든 인수(경로)만 따옴표로 감싼다.
    $arguments = @($SshOptions + @("-o", "ExitOnForwardFailure=yes", "-N", "-L", "127.0.0.1:${port}:${coreIp}:8080", $ProdHost) |
        ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $script:prodTunnel = Start-Process -FilePath $SshCommand -ArgumentList $arguments -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $runDir "tunnel.log") -RedirectStandardError (Join-Path $runDir "tunnel.err")
    $url = "http://127.0.0.1:$port"
    for ($i = 0; $i -lt 30; $i++) {
        if ($script:prodTunnel.HasExited) { Fail "SSH tunnel to ${coreIp}:8080 exited (see tunnel.err)" }
        try { Get-Text "$url/actuator/health" | Out-Null; return $url } catch { Start-Sleep -Milliseconds 500 }
    }
    Fail "SSH tunnel to ${coreIp}:8080 did not answer within 15s"
}

function Stop-ProdTunnel {
    if ($script:prodTunnel -and -not $script:prodTunnel.HasExited) { Stop-Process -Id $script:prodTunnel.Id -Force -ErrorAction SilentlyContinue }
}

# 앞 실행의 부하가 빠질 때까지 기다린다. health UP, 연결 대기 0, 바쁜 요청 스레드 5개 이하를 5초 간격으로 최대 3분 본다.
# (2026-10-17 운영 사다리에서 쓴 회복 확인과 같다.) 지표는 run-step의 Get-SampleUrl이 거른 주소로 읽는다.
function Wait-CoreRecovered([string]$url, [string]$sampleUrl) {
    for ($i = 0; $i -lt 36; $i++) {
        try {
            $health = Get-Text "$url/actuator/health"
            $metrics = Get-Text $sampleUrl
            $pending = [regex]::Match($metrics, '(?m)^hikaricp_connections_pending\{[^}]*\} ([0-9.eE+-]+)').Groups[1].Value
            $busy = [regex]::Match($metrics, '(?m)^tomcat_threads_busy_threads\{[^}]*\} ([0-9.eE+-]+)').Groups[1].Value
            if ($health -match '"status":"UP"' -and [double]$pending -eq 0 -and [double]$busy -le 5) { return }
            Write-Host "waiting for Core to recover: pending=$pending busy=$busy"
        } catch { Write-Host "waiting for Core to recover: $($_.Exception.Message)" }
        Start-Sleep -Seconds 5
    }
    Fail "Core did not recover within 3 minutes (health UP, hikari pending 0, tomcat busy <= 5)"
}
