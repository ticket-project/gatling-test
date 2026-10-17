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

# 실행 전 점검: Core 이미지(commit), 컨테이너 IP, DB 규모, 다음 깨끗한 대형 회차, 앞단 설정.
# 한 번 쓴 대형 회차에는 03 실행이 만든 주문이 남는다. 주문이 없는 가장 작은 920000001~ 회차를 다음 회차로 고른다.
# orders.performance_id에는 단독 인덱스가 없으므로 회차마다 묻지 않고 NOT IN 한 번(주문 한 번 훑기)으로 구한다.
function Get-ProdPreflight {
    # here-string은 끝 줄바꿈이 없으므로 이어 붙일 때 줄바꿈을 넣는다.
    $body = $prodPsqlPrelude + "`n" + @'
image=$(sudo docker inspect -f '{{.Config.Image}}' ticket-be)
coreip=$(sudo docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' ticket-be | awk '{print $1}')
echo "image=$image"
echo "coreIp=$coreip"
echo "db=$(psqlq "select (select count(*) from members where deleted_at is null and email like 'loadtest%@test.com' and role = 'MEMBER'), (select count(*) from orders), current_setting('max_connections'), (select count(*) from performances where id between 920000001 and 929999999), (select min(id) from performances where id between 920000001 and 929999999 and id not in (select performance_id from orders where performance_id between 920000001 and 929999999))")"
echo '---conditions---'
echo '# ticket-be image'; echo "$image"
echo '# nginx'; sudo docker exec ticket-nginx nginx -T 2>/dev/null | grep -nE 'worker_processes|worker_rlimit_nofile|worker_connections|limit_req|limit_conn|gzip' || true
echo '# compose'; grep -nE 'JAVA_TOOL_OPTIONS|Xmx|mem_limit|cpus|HIKARI|TOMCAT' /home/ubuntu/docker-compose.yml || true
echo '# free -m'; free -m
echo '# docker stats'; sudo docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
'@
    $lines = Invoke-ProdBash $body
    $marker = [Array]::IndexOf($lines, '---conditions---')
    if ($marker -lt 0) { Fail "unexpected preflight output from $ProdHost" }
    $kv = @{}
    foreach ($line in $lines[0..($marker - 1)]) { if ($line -match '^(\w+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
    $db = $kv.db -split ','
    if ($db.Count -lt 5) { Fail "could not read production database counts: $($kv.db)" }
    [pscustomobject]@{
        Image = $kv.image; CoreIp = $kv.coreIp
        Commit = ($kv.image -split ':')[-1]
        Members = [long]$db[0]; Orders = [long]$db[1]; MaxConnections = [int]$db[2]
        LargePerformances = [int]$db[3]; NextPerformance = $(if ($db[4]) { [long]$db[4] } else { 0 })
        Conditions = ($lines[($marker + 1)..($lines.Count - 1)] -join [Environment]::NewLine)
    }
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
# (2026-10-17 운영 사다리에서 쓴 회복 확인과 같다.)
function Wait-CoreRecovered([string]$url) {
    for ($i = 0; $i -lt 36; $i++) {
        try {
            $health = Get-Text "$url/actuator/health"
            $metrics = Get-Text "$url/actuator/prometheus"
            $pending = [regex]::Match($metrics, '(?m)^hikaricp_connections_pending\{[^}]*\} ([0-9.eE+-]+)').Groups[1].Value
            $busy = [regex]::Match($metrics, '(?m)^tomcat_threads_busy_threads\{[^}]*\} ([0-9.eE+-]+)').Groups[1].Value
            if ($health -match '"status":"UP"' -and [double]$pending -eq 0 -and [double]$busy -le 5) { return }
            Write-Host "waiting for Core to recover: pending=$pending busy=$busy"
        } catch { Write-Host "waiting for Core to recover: $($_.Exception.Message)" }
        Start-Sleep -Seconds 5
    }
    Fail "Core did not recover within 3 minutes (health UP, hikari pending 0, tomcat busy <= 5)"
}
