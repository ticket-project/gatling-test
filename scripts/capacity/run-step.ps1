param(
    [double]$UsersPerSecond = 0,
    # 비우면(0) -Prod는 주문이 없는 다음 대형 회차를 운영 DB에서 고른다. 그 밖의 대상은 반드시 준다.
    [long]$PerformanceId = 0,
    [int]$DurationSeconds = 30,
    [string]$CoreUrl = "http://localhost:8080",
    [string]$Label = "step",
    # 03: 고정 좌석 하나를 바로 주문하는 단순 예매. 03-3: 인기 공연 오픈(인기석 쏠림, 이선좌 재시도, 고민, 이탈·취소, 매진).
    # 내세울 수용량은 03-3으로 잰다(2026-11-04 사용자 결정, ticket docs/core-capacity.md).
    [ValidateSet("03", "03-3")]
    [string]$Scenario = "03",
    [string]$MemberIdsFile = "",
    [string]$JwtSecret = $env:LOADTEST_JWT_SECRET,
    [double]$AdmissionTolerancePercent = 15,
    [int]$ThreadDumps = 0,
    [switch]$OperationalConfirmation,
    # 운영 Core는 actuator를 밖에 열지 않는다. health·prometheus는 SSH 터널 같은 다른 주소로 읽는다. 비우면 CoreUrl.
    [string]$MetricsUrl = "",
    # 원격 부하 발생기(ubuntu@IP). 준비·지표 수집·판정은 이 PC에서 하고 Gatling만 그 EC2에서 SSH로 돌린다.
    [string]$LoadHost = "",
    [string]$KeyPath = "",
    [string]$RemoteProjectDir = "~/gatling-test",
    # reset-local.ps1이 기록하지 않는 대상(운영)의 Core commit. steps.md의 commit 칸에 쓴다.
    [string]$CoreCommit = "",
    # 운영 측정 묶음. 운영 호스트에 SSH로 들어가 지표 터널·JWT secret·회원 ID·다음 회차·실행 전 점검·회복 대기를
    # 스스로 한다(prod-target.ps1). 사람이 줄 것은 부하 발생기 IP(-LoadHost)뿐이다.
    # 키는 -KeyPath, 비우면 ~/.ssh/ticket-test-key-01.pem부터 찾는다(운영 호스트와 부하 발생기가 같은 키).
    [switch]$Prod,
    [string]$ProdHost = "ubuntu@oneticket.site",
    # -Prod 준비와 점검만 하고 부하는 보내지 않는다. Core EC2를 켠 뒤나 다른 PC에서 준비가 됐는지 볼 때 쓴다.
    [switch]$CheckOnly
)

# 03 고정 조건(CoreAdmissionCapacitySimulation) 한 단계를 콘솔 없이 실행하고 판정한다.
# 판정 기준의 원본은 ticket docs/core-capacity.md "판정 기준"이다(2026-10-17 개정).
# FAIL: 기술 실패, 과부하(E6003), Gatling 쪽 요청별 응답 시간, 밀림(처리가 입장을 못 따라감).
# INVALID: 입장 속도·부하 발생기 GC·시작 사용자 수가 어긋났거나 비즈니스 거절이 있다(데이터 문제).
# 체류 p99와 Core 쪽 시간(/actuator/prometheus 히스토그램의 실행 전후 차이)은 기록만 하고 판정에 쓰지 않는다.
# 메시지는 영어로 쓴다. Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트의 한글 문자열을 깨뜨린다.

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlew = Join-Path $repo "gradlew.bat"
$gatlingProject = Join-Path $repo "load-tests\gatling"
$localSecret = "0123456789abcdef0123456789abcdef"
# 판정 기준값(core-capacity.md). 요청 이름은 CoreBookingFlow의 http(...) 이름이다. 값은 p95, p99 상한(ms).
$TechnicalFailureLimitPercent = 0.1
# 03은 점유 좌석 응답에 회차 요약이 들어 있어 요약 조회를, 주문 생성 응답이 상태를 담고 있어 주문 조회를 부르지 않는다(2026-10-19).
$isRealistic = $Scenario -eq "03-3"
$LatencyLimits = if ($isRealistic) {
    # 03-3은 화면이 부르는 조회·쓰기를 모두 본다. 조회는 0.5/1초, 쓰기(선택·주문·해제·취소)는 1/2초.
    # 03-3도 03처럼 요약과 생성한 주문을 따로 부르지 않는다(2026-11-05). 요약은 점유 좌석 응답에, 주문 상태는 주문 생성 응답에 있다.
    [ordered]@{
        "venue layout" = @(500, 1000); "show seat map" = @(500, 1000)
        "seat status" = @(500, 1000)
        "select seat" = @(1000, 2000); "create order" = @(1000, 2000)
        "release all selections" = @(1000, 2000); "cancel order" = @(1000, 2000)
    }
} else {
    [ordered]@{
        "seat status" = @(500, 1000)
        "select seat" = @(1000, 2000); "create order" = @(1000, 2000)
    }
}
# 밀림(03만): 주입이 끝날 때 Core 안에 남은 사용자가 부하 x 이 초보다 많거나, 마지막 몇 초 입장 구간의 체류 중앙값이 상한을 넘으면 FAIL.
# 03-3은 사람이 일부러 1~6초 고민하며 머물러 이 기준이 맞지 않는다. 대신 응답 시간을 1분 구간마다 본다.
$BacklogSeconds = 1
$TailWindowSeconds = 5
$TailMedianLimitMillis = 1000
# 1분 구간 판정은 2분 이상 실행에서만 한다. 표본이 적은 구간(첫·끝 부분 분, 드문 요청)은 p99가 최댓값과 같아 흔들리므로 뺀다.
# ponytail: 고정 100건. 드문 요청(취소 등)의 1분 판정이 필요해지면 구간을 넓힌다.
$MinuteMinCount = 100
# 03-3에서 정상으로 보는 비즈니스 거절: 이선좌(E4001 다른 사람이 선택, E6000 이미 선점)와 매진(빈 좌석 없음).
# 그 밖의 거절(진행 중 주문 E5004, 선점 한도 E6001 등)은 회원·데이터 준비 문제라 측정을 무효로 본다.
$ExpectedRejections = "BUSINESS_REJECTED_E4001", "BUSINESS_REJECTED_E6000", "BUSINESS_REJECTED_NO_AVAILABLE_SEAT"
# 긴 실행 자동 중단: 요청 스레드가 이 초 동안 연달아 전부 바쁘거나 지표 수집이 막히면 Gatling을 멈추고 FAIL로 끝낸다.
# 무너진 채 수십 분 부하를 주지 않기 위해서다. 30초 사다리에는 걸지 않는다(무너진 모습까지 기록해야 진단한다).
$AbortAfterSeconds = if ($DurationSeconds -gt 120) { 30 } else { 0 }

# -Prod면 prod-target.ps1이 진짜 Stop-ProdTunnel로 덮어쓴다.
function Stop-ProdTunnel {}
function Fail([string]$message) { Stop-ProdTunnel; Write-Host "ABORT: $message" -ForegroundColor Red; exit 2 }
# 예상하지 못한 예외도 중단(2)으로 끝낸다. 그대로 두면 종료 코드 1(FAIL과 같은 값)이 되어 판정과 섞인다.
trap { Fail "unexpected error: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))" }
# Start-Process -Wait는 자식 프로세스까지 기다린다. gradlew가 새로 띄운 Gradle 데몬은 계속 살아 있으므로 끝나지 않는다.
# 띄운 프로세스만 기다린다. Handle을 먼저 읽어야 Windows PowerShell 5.1에서 ExitCode가 남는다.
# $abortFile이 생기면 $onAbort(원격 Gatling 정리 등)를 부르고 띄운 프로세스 트리를 끝낸다.
function Invoke-Process([string]$file, [string[]]$arguments, [string]$out, [string]$err, [string]$abortFile = "", [scriptblock]$onAbort = $null) {
    $p = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $repo -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle
    while (-not $p.WaitForExit(1000)) {
        if ($abortFile -and (Test-Path $abortFile)) {
            if ($onAbort) { & $onAbort }
            # $onAbort가 이미 끝낸 하위 프로세스는 taskkill이 오류를 낸다. PowerShell 5.1은 그 stderr를 예외로 바꾸므로 cmd 안에서 버린다.
            & cmd /c "taskkill /T /F /PID $($p.Id) >nul 2>&1"
            break
        }
    }
    $p.WaitForExit()
    $p.ExitCode
}
# actuator 응답은 content type이 text가 아니라서 .Content가 바이트 배열로 온다. 항상 UTF-8로 읽는다.
function Get-Text([string]$url, [hashtable]$headers = @{}) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $headers -TimeoutSec 10
    [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
}
function Write-Utf8([string]$path, [string]$value) {
    [IO.File]::WriteAllText($path, $value + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
# 1초 수집·회복 대기·기동 시간은 이 지표만 쓴다. 전체 /actuator/prometheus(약 280KB)를 부하 중 1초마다 만들게 하면
# 2 vCPU 운영 Core에 측정 자체가 짐이 된다(capacity-log P-020). 전체 원문은 실행 전후 한 번씩만 받는다.
$SampleNames = "hikaricp_connections_active", "hikaricp_connections_pending", "tomcat_threads_busy_threads",
    "process_cpu_usage", "system_cpu_usage", "process_uptime_seconds"
# includedNames로 거른 주소를 돌려준다. 빠진 지표가 있으면(Core가 거르지 못하면) 전체 주소로 돌아간다.
function Get-SampleUrl([string]$base) {
    $url = "$base/actuator/prometheus?includedNames=" + ($SampleNames -join '%2C')
    $text = Get-Text $url
    $missing = @($SampleNames | Where-Object { $text -notmatch "(?m)^$_\{" })
    if ($missing.Count -eq 0) { return $url }
    Write-Host "warning: filtered metrics miss $($missing -join ', '); sampling the full /actuator/prometheus"
    "$base/actuator/prometheus"
}

# --- 대상과 입력 확인 ----------------------------------------------------------------------------
if ($Prod) {
    # 운영 묶음의 기본값. 터널·secret·회차는 결과 폴더를 만든 뒤 "운영 준비"에서 채운다.
    if (-not $PSBoundParameters.ContainsKey('CoreUrl')) { $CoreUrl = "https://oneticket.site" }
    $OperationalConfirmation = $true
    if (-not $MemberIdsFile) { $MemberIdsFile = Join-Path $repo "scripts\core-capacity\member-ids.txt" }
    if (-not $LoadHost -and -not $CheckOnly) { Fail "-Prod needs -LoadHost ubuntu@<load generator IP>" }
    if ($CheckOnly -and -not $PSBoundParameters.ContainsKey('Label')) { $Label = "check" }
} elseif ($CheckOnly) { Fail "-CheckOnly works only with -Prod" }
if (-not $CheckOnly -and $UsersPerSecond -le 0) { Fail "-UsersPerSecond is required" }
if (-not $Prod -and $PerformanceId -le 0) { Fail "-PerformanceId is required" }
# compose가 127.0.0.1에만 연 포트는 ::1(IPv6 localhost)로는 닿지 않고, Windows는 거절된 연결을 약 2초 재시도한다.
# localhost를 쓰면 1초 지표 수집(타임아웃 2초)이 매번 실패하므로 주소를 127.0.0.1로 고정한다.
$CoreUrl = $CoreUrl -replace '^(https?://)localhost(?=[:/]|$)', '${1}127.0.0.1'
if (-not $MetricsUrl) { $MetricsUrl = $CoreUrl }
$MetricsUrl = $MetricsUrl -replace '^(https?://)localhost(?=[:/]|$)', '${1}127.0.0.1'
$coreUri = [Uri]$CoreUrl
$isLocal = @("localhost", "127.0.0.1", "::1") -contains $coreUri.Host
if (-not $isLocal -and -not $OperationalConfirmation) { Fail "non-local target requires -OperationalConfirmation" }
if (-not $isLocal -and -not $MemberIdsFile) { Fail "non-local target requires -MemberIdsFile (real ACTIVE member ids)" }
$users = [int][Math]::Ceiling($UsersPerSecond * $DurationSeconds)
$simulation = if ($isRealistic) { "CoreRealisticUserMixSimulation" } else { "CoreAdmissionCapacitySimulation" }
$bookingScenario = if ($isRealistic) { "CORE_REALISTIC_USER_MIX" } else { "CORE_ADMISSION_CAPACITY" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$scenarioTag = if ($isRealistic) { "-03-3" } else { "" }
$runDir = Join-Path $repo ("distributed-results-join\capacity\{0}-{1}{2}-{3}ups-{4}s" -f $stamp, $Label, $scenarioTag, $UsersPerSecond, $DurationSeconds)
New-Item -ItemType Directory -Force $runDir | Out-Null
$runId = [guid]::NewGuid().ToString()
Write-Host "run dir: $runDir"

# --- 운영 준비(-Prod) -------------------------------------------------------------------------------
# 운영 호스트에서 commit·컨테이너 IP를 읽고, 지표 터널을 열고, JWT secret을 메모리로 받고, 회원 ID 파일이 모자라면 받고,
# 앞 실행의 부하가 빠질 때까지 기다린다. DB 규모·앞단 설정은 -CheckOnly에서만, 다음 회차는 -PerformanceId가 없을 때만 묻는다.
# 사다리는 -CheckOnly가 알려 준 회차부터 -PerformanceId를 넘겨 실행마다 DB를 건드리지 않는다(prod-target.ps1 Get-ProdPreflight).
# Core EC2는 측정할 때만 켜므로 JVM이 차가울 수 있다. 기동 시간(coreUptimeSeconds)이 짧으면 첫 실행은 예열로 쓴다.
if ($Prod) {
    . (Join-Path $repo "scripts\distributed-common.ps1")
    . (Join-Path $PSScriptRoot "prod-target.ps1")
    $LocalProjectDir = $repo
    try { Initialize-SshSession -RunDir $runDir } catch { Fail "SSH setup failed: $($_.Exception.Message)" }
    $prodInfo = Get-ProdPreflight -Full:$CheckOnly -FindPerformance:($PerformanceId -le 0)
    Write-Utf8 (Join-Path $runDir "server-conditions.txt") $prodInfo.Conditions
    $MetricsUrl = Start-ProdTunnel $prodInfo.CoreIp
    $sampleUrl = Get-SampleUrl $MetricsUrl
    if (-not $PSBoundParameters.ContainsKey('JwtSecret')) { $JwtSecret = Get-ProdJwtSecret }
    if (-not $CoreCommit) { $CoreCommit = $prodInfo.Commit.Substring(0, [Math]::Min(8, $prodInfo.Commit.Length)) }
    if ($PerformanceId -le 0) {
        if ($prodInfo.NextPerformance -le 0) { Fail "no large performance is left after the last load-test order; seed more large performances" }
        $PerformanceId = $prodInfo.NextPerformance
    }
    # 다른 PC나 예전(Oracle) 운영에서 남은 짧은 파일을 그대로 쓰면 회원이 겹쳐 비즈니스 거절로 회차를 버린다. 모자라면 다시 받는다.
    if (-not (Test-Path $MemberIdsFile) -or @(Get-Content $MemberIdsFile).Count -lt $users) {
        Save-ProdMemberIds $MemberIdsFile ([Math]::Max(10000, $users))
    }
    Wait-CoreRecovered $MetricsUrl $sampleUrl
    $uptime = [regex]::Match((Get-Text $sampleUrl), '(?m)^process_uptime_seconds\{[^}]*\} ([0-9.eE+-]+)').Groups[1].Value
    $prodConditions = [ordered]@{ prodHost = $ProdHost; image = $prodInfo.Image; coreCommit = $CoreCommit
        coreUptimeSeconds = [Math]::Round([double]$uptime); membersEstimate = $prodInfo.MembersEstimate; ordersEstimate = $prodInfo.OrdersEstimate
        maxConnections = $prodInfo.MaxConnections; largePerformances = $prodInfo.LargePerformances; performanceId = $PerformanceId
        loadHost = $LoadHost; memberIdsFile = $MemberIdsFile; sampleUrlFiltered = $sampleUrl -match 'includedNames' }
    Write-Utf8 (Join-Path $runDir "prod-conditions.json") ($prodConditions | ConvertTo-Json)
    $line = "prod: commit $CoreCommit, core up $($prodConditions.coreUptimeSeconds)s, performance $PerformanceId"
    if ($CheckOnly) {
        $line += ", members ~$($prodInfo.MembersEstimate), orders ~$($prodInfo.OrdersEstimate), max_connections $($prodInfo.MaxConnections), large performances $($prodInfo.LargePerformances)"
    }
    Write-Host $line
    if ($CheckOnly) { Stop-ProdTunnel; Write-Host "check: OK (no load sent)"; exit 0 }
}
# 부하 픽스처는 공연·회차·좌석이 모두 idBase + n이다(ticket seed LoadTestFixtureSeeder).
# 표준 910000001~(2,000석), 대형 920000001~(15,000석). 좌석은 idBase + 1부터 연속이다. 공연(show) ID도 idBase + 1이다(03-3 배치도 조회).
$seatStartId = [long]([Math]::Floor($PerformanceId / 10000000) * 10000000 + 1)
$showId = $seatStartId

$health = Get-Text "$MetricsUrl/actuator/health"
if ($health -notmatch '"status":"UP"') { Fail "Core health is not UP: $health" }
try { Get-Text "$CoreUrl/api/v1/performances/$PerformanceId/summary" | Out-Null } catch { Fail "performance $PerformanceId not found: $($_.Exception.Message)" }

# 로컬 Core가 측정에 맞지 않게 떠 있으면 알린다(capacity-log P-006).
# reset-local.ps1로 띄운 Core라면 core-local.json에 commit·풀·seed 조건이 있다.
$coreWarnings = @()
$coreInfo = $null
if ($isLocal) {
    $infoFile = Join-Path $repo "distributed-results-join\capacity\core-local.json"
    if (Test-Path $infoFile) { $coreInfo = Get-Content $infoFile -Raw | ConvertFrom-Json }
    if ($coreInfo -and $coreInfo.container) {
        # reset-local.ps1 -Compose로 띄운 컨테이너 Core. 호스트 포트는 Docker 포트 중계가 잡으므로 PID 대신 컨테이너와 포트를 본다.
        # 컨테이너는 compose의 java -jar로만 뜨므로 디버거·C2 꺼짐 검사는 하지 않는다.
        $running = & docker inspect -f "{{.State.Running}}" $coreInfo.container
        if ($running -ne "true" -or $coreInfo.port -ne $coreUri.Port) {
            $coreWarnings += "Core container $($coreInfo.container) is not running on port $($coreUri.Port)"
            $coreInfo = $null
        }
    } else {
        $listener = Get-NetTCPConnection -LocalPort $coreUri.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)").CommandLine
            if ($cmd -match "jdwp") { $coreWarnings += "Core runs with a debugger agent (jdwp)" }
            if ($cmd -match "TieredStopAtLevel=1") { $coreWarnings += "Core runs with TieredStopAtLevel=1 (C2 compiler off)" }
        }
        if (-not $listener -or -not $coreInfo -or $coreInfo.pid -ne $listener.OwningProcess) {
            $coreWarnings += "Core was not started by reset-local.ps1, so commit and conditions are unknown"
            $coreInfo = $null
        }
    }
}

# --- 토큰과 피더 생성, 회차 오염 검사 --------------------------------------------------------------
$databaseInfo = $null
if ($isLocal -and $coreInfo) {
    # 기동 때 쓴 환경을 읽어 Core와 같은 DB·JWT secret을 사용한다.
    foreach ($line in Get-Content -Encoding UTF8 $coreInfo.envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Set-Item "env:$($Matches[1])" $Matches[2] }
    }
    $snapshotUrl = if ($env:SPRING_DATASOURCE_URL) { $env:SPRING_DATASOURCE_URL } else { "jdbc:postgresql://localhost:5432/ticket" }
    if ($snapshotUrl -notmatch '^jdbc:postgresql://') { Fail "local snapshot requires PostgreSQL" }
    if (([Uri]$snapshotUrl.Substring(5)).GetLeftPart([UriPartial]::Path) -ne $coreInfo.databaseTarget) {
        Fail "environment database differs from the database recorded at Core startup"
    }
    if (-not $JwtSecret) { $JwtSecret = $env:JWT_SECRET }
    $snapshotArgs = @("-p", $gatlingProject, "snapshotLocalPostgres", "-q",
        "-DlocalSnapshotDir=$runDir", "-DlocalSnapshotMembers=$users")
    $snapshotExit = Invoke-Process $gradlew $snapshotArgs (Join-Path $runDir "database-snapshot.log") (Join-Path $runDir "database-snapshot.err")
    if ($snapshotExit -ne 0) { Fail "local PostgreSQL snapshot failed (see database-snapshot.err)" }
    $databaseInfo = Get-Content -Encoding UTF8 (Join-Path $runDir "database.properties") -Raw | ConvertFrom-StringData
    if (-not $MemberIdsFile) { $MemberIdsFile = Join-Path $runDir "member-ids.txt" }
}
if (-not $JwtSecret) {
    if ($isLocal) { $JwtSecret = $localSecret } else { Fail "set -JwtSecret or LOADTEST_JWT_SECRET for a non-local target" }
}
# 토큰 서명 비밀값을 명령행에 넣지 않는다. Java 생성기는 환경변수로 읽는다.
$env:LOADTEST_JWT_SECRET = $JwtSecret
$tokensFile = Join-Path $runDir "access-tokens.txt"
$feederFile = Join-Path $runDir "feeder.csv"
$genArgs = @("-p", $gatlingProject, "generateAccessTokens", "-q",
    "-Doutput=$tokensFile", "-DtokenCount=$users", "-DjwtIssuer=ticket",
    # 토큰은 실행 전에 한꺼번에 만든다. 긴 실행(03-3 확정 실행은 1시간 가까이)의 마지막 사용자 토큰이 만료되지 않게 실행 길이 + 30분을 준다.
    "-DsyntheticMemberStartId=1", "-DsyntheticJwtRole=MEMBER", "-DsyntheticTokenTtlSeconds=$([Math]::Max(3600, $DurationSeconds + 1800))",
    "-DbookingFeederOutput=$feederFile", "-DbookingSeatStartId=$seatStartId")
if ($MemberIdsFile) { $genArgs += "-DmemberIdsFile=$((Resolve-Path $MemberIdsFile).Path)" }
$genExit = Invoke-Process $gradlew $genArgs (Join-Path $runDir "token-generation.log") (Join-Path $runDir "token-generation.err")
if ($genExit -ne 0) { Fail "token generation failed (see token-generation.err)" }

# 한 번 쓴 회차는 좌석이 선택·선점돼 있어 결과가 섞인다. 실행 전에 전부 AVAILABLE인지 본다.
$firstToken = (Get-Content $tokensFile -TotalCount 1).Trim()
$seats = (ConvertFrom-Json (Get-Text "$CoreUrl/api/v1/performances/$PerformanceId/seats/status" @{ Authorization = "Bearer $firstToken" })).data.seats
$available = @($seats | Where-Object { $_.status -eq "AVAILABLE" }).Count
# 03은 사용자마다 고유 좌석이 있어야 한다. 03-3은 빈 좌석을 응답에서 고르고 매진되면 거절받으므로 좌석 수와 상관없다.
if ($available -ne $seats.Count -or (-not $isRealistic -and $available -lt $users)) {
    Fail "performance $PerformanceId is not clean or too small: available=$available of $($seats.Count), need $users. Use a fresh or larger performance."
}
# 피더는 seatStartId부터 연속 좌석을 쓴다. 그 가정이 이 회차에서 맞는지 응답으로 확인한다.
$seatIds = $seats | ForEach-Object { [long]$_.seatId } | Sort-Object
if ($seatIds[0] -ne $seatStartId -or $seatIds[-1] - $seatIds[0] + 1 -ne $seatIds.Count) {
    Fail "seat ids of performance $PerformanceId are $($seatIds[0])..$($seatIds[-1]) ($($seatIds.Count) seats), not consecutive from $seatStartId"
}

# --- 원격 부하 발생기 준비 ----------------------------------------------------------------------
# 이 PC의 작업 트리를 EC2에 풀어 Gatling 코드를 맞추고, 토큰·피더를 실행 폴더에 올린다.
# 토큰은 이 PC에서 만들었으므로 EC2에는 JWT secret이 가지 않는다.
$remoteDir = ""
if ($LoadHost) {
    # -Prod면 운영 준비에서 이미 SSH 세션을 만들었다(같은 키).
    if (-not $Prod) { . (Join-Path $repo "scripts\distributed-common.ps1"); $LocalProjectDir = $repo }
    try {
        if (-not $Prod) { Initialize-SshSession -RunDir $runDir }
        # 부하 발생기 EC2는 측정할 때만 켠다. Java 21이 없으면(새 인스턴스) 설치한다. 설치는 디스크에 남는다.
        & $SshCommand @SshOptions $LoadHost 'v=$(java -version 2>&1 | head -1 | grep -oE [0-9]+ | head -1); [ ${v:-0} -ge 21 ] || (sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless >/dev/null)'
        if ($LASTEXITCODE -ne 0) { throw "Java 21 install failed" }
        $archive = New-ProjectArchive -RunDir $runDir
        Sync-RemoteProject -HostName $LoadHost -ArchivePath $archive -StartedAt $stamp
        Remove-Item $archive
        Test-RemoteProject -HostName $LoadHost
    } catch { Fail "load generator $LoadHost is not ready: $($_.Exception.Message)" }
    $remoteDir = "/tmp/capacity-" + (Split-Path $runDir -Leaf)
    & $SshCommand @SshOptions $LoadHost "mkdir -p $remoteDir"
    if ($LASTEXITCODE -ne 0) { Fail "could not create $remoteDir on $LoadHost" }
    & $ScpCommand @ScpOptions $tokensFile $feederFile "${LoadHost}:$remoteDir/"
    if ($LASTEXITCODE -ne 0) { Fail "could not upload tokens and feeder to $LoadHost" }
}

# --- 실행 전 상태 ------------------------------------------------------------------------------
function Get-Metrics { Get-Text "$MetricsUrl/actuator/prometheus" }
function Get-Gauge([string]$text, [string]$name) {
    $m = [regex]::Match($text, "(?m)^$name\{[^}]*\} ([0-9.eE+-]+)")
    if ($m.Success) { [double]$m.Groups[1].Value } else { $null }
}
$before = Get-Metrics
# 원문을 남겨 두면 연결 점유 시간, 저장소 메서드별 호출, Core GC 같은 다른 지표도 실행 뒤에 전후 차이로 볼 수 있다.
Write-Utf8 (Join-Path $runDir "prometheus-before.txt") $before
$hikariMax = Get-Gauge $before "hikaricp_connections_max"
$tomcatMax = Get-Gauge $before "tomcat_threads_config_max_threads"
# compose PostgreSQL의 WAL 누적값(capacity-log P-016). 커밋이 WAL을 디스크에 내리는 횟수와 시간을 전후 차이로 본다.
# fsync·write 시간은 PostgreSQL의 track_wal_io_timing이 켜져 있을 때만 쌓인다(마지막 열).
$pgContainer = if ($coreInfo -and $coreInfo.container) { "ticket-postgres" } else { "" }
$walSql = "select (select sum(writes) from pg_stat_io where object = 'wal'), (select sum(write_time) from pg_stat_io where object = 'wal'), " +
    "(select sum(fsyncs) from pg_stat_io where object = 'wal'), (select sum(fsync_time) from pg_stat_io where object = 'wal'), wal_records, wal_fpi, wal_bytes, " +
    "(select xact_commit from pg_stat_database where datname = current_database()), current_setting('track_wal_io_timing') from pg_stat_wal"
function Get-Wal { if ($pgContainer) { "$(& docker exec $pgContainer psql -U ticket -d ticket -At -F ',' -c $walSql)".Trim() } }
$walBefore = Get-Wal

# 중복 판매 확인(03-3): 한 좌석에 살아 있던 기간이 겹치는 주문이 둘 이상이면 같은 좌석을 두 사람에게 판 것이다.
# 주문 테이블에는 회차 인덱스가 없어 회차로 거르면 운영 주문 500만 건을 다 읽는다(운영 점검 최소, P-020).
# 실행 직전 주문·주문좌석 ID 최댓값(PK라 바로 읽힌다)을 기억해 두고, 실행 뒤에는 그보다 큰 ID(이번 실행이 만든 행)만 읽는다.
function Invoke-BookingSql([string]$sql) {
    if ($Prod) { return ((Invoke-ProdBash ($prodPsqlPrelude + "`npsqlq ""$sql""")) -join "").Trim() }
    if ($pgContainer) { return "$(& docker exec $pgContainer psql -U ticket -d ticket -At -F ',' -c $sql)".Trim() }
    $null
}
$orderBaseline = $null
if ($isRealistic) {
    $line = Invoke-BookingSql "select (select coalesce(max(id), 0) from orders), (select coalesce(max(id), 0) from order_seats)"
    if ($line -match '^(\d+),(\d+)$') { $orderBaseline = @([long]$Matches[1], [long]$Matches[2]) }
}

$ordersBefore = if ($databaseInfo) { [long]$databaseInfo.orders } else { $null }

# --- 1초 단위 Core 지표 수집과 Gatling 실행 ----------------------------------------------------------
$metricsFile = Join-Path $runDir "core-metrics.tsv"
$stopFile = Join-Path $runDir ".stop-sampler"
# 진단 실행(-ThreadDumps N): Core가 막히기 시작한 순간부터 3초 간격으로 스레드 덤프 N장을 thread-dump-<n>.txt로 남긴다.
# 덤프하는 동안 Core가 잠깐 멈추므로 진단 실행의 숫자는 판정과 사다리에 쓰지 않는다.
# 컨테이너 Core는 이미지가 JRE라 jcmd가 없다. kill -3(SIGQUIT)으로 JVM이 stdout에 덤프를 쓰게 하고 docker logs에서 잘라 온다.
$jcmd = ""; $corePid = 0; $container = ""
if ($ThreadDumps -gt 0) {
    if (-not $coreInfo) { Fail "-ThreadDumps needs a local Core started by reset-local.ps1" }
    if ($coreInfo.container) {
        $container = $coreInfo.container
    } else {
        $jcmd = Join-Path (Split-Path $coreInfo.java) "jcmd.exe"
        if (-not (Test-Path $jcmd)) { Fail "jcmd not found next to Core java: $jcmd" }
        $corePid = $coreInfo.pid
    }
}
if (-not $sampleUrl) { $sampleUrl = Get-SampleUrl $MetricsUrl }
$abortFile = Join-Path $runDir ".abort"
$sampler = Start-Job -ArgumentList $sampleUrl, $metricsFile, $stopFile, $ThreadDumps, $jcmd, $corePid, $runDir, $container, $abortFile, $tomcatMax, $AbortAfterSeconds -ScriptBlock {
    param($url, $file, $stop, $dumps, $jcmd, $corePid, $dir, $container, $abortFile, $busyLimit, $abortAfter)
    Set-Content $file "time`thikari_active`thikari_pending`ttomcat_busy`tprocess_cpu`tsystem_cpu"
    $names = "hikaricp_connections_active", "hikaricp_connections_pending", "tomcat_threads_busy_threads", "process_cpu_usage", "system_cpu_usage"
    $taken = 0; $lastDump = [datetime]::MinValue; $stuckSince = $null
    while (-not (Test-Path $stop)) {
        $t = Get-Date -Format "HH:mm:ss"
        # 자동 중단: 요청 스레드가 전부 바쁘거나 지표 수집이 시간 초과인 상태가 $abortAfter초 이어지면 .abort를 만든다.
        $stuck = $true
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2
            $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
            $values = foreach ($n in $names) { $m = [regex]::Match($text, "(?m)^$n\{[^}]*\} ([0-9.eE+-]+)"); if ($m.Success) { $m.Groups[1].Value } else { "" } }
            Add-Content $file ($t + "`t" + ($values -join "`t"))
            $stuck = $busyLimit -and [double]$values[2] -ge $busyLimit
            # ponytail: 고정 문턱(연결 대기 > 0 또는 바쁜 요청 스레드 20개 이상). 문턱 아래에서 FAIL하면 덤프가 안 남으니 그때 매개변수로 뺀다.
            if ($taken -lt $dumps -and ([double]$values[1] -gt 0 -or [double]$values[2] -ge 20) -and ((Get-Date) - $lastDump).TotalSeconds -ge 3) {
                $taken++; $lastDump = Get-Date
                if ($container) {
                    $since = (Get-Date).ToUniversalTime().AddSeconds(-2).ToString("yyyy-MM-ddTHH:mm:ssZ")
                    & docker exec $container kill -3 1
                    Start-Sleep -Milliseconds 1500
                    $log = @(& docker logs --since $since $container 2>&1 | ForEach-Object { "$_" })
                    $start = [Array]::FindLastIndex($log, [Predicate[string]] { param($line) $line -like "Full thread dump*" })
                    $dump = if ($start -ge 0) { $log[$start..($log.Count - 1)] -join [Environment]::NewLine } else { "no thread dump found in docker logs" }
                } else {
                    $dump = (& $jcmd $corePid Thread.print -l) -join [Environment]::NewLine
                }
                [IO.File]::WriteAllText((Join-Path $dir "thread-dump-$taken.txt"), $dump, [Text.UTF8Encoding]::new($false))
            }
        } catch { Add-Content $file "$t`tTIMEOUT" }
        if (-not $stuck) { $stuckSince = $null } elseif (-not $stuckSince) { $stuckSince = Get-Date }
        if ($abortAfter -gt 0 -and $stuckSince -and ((Get-Date) - $stuckSince).TotalSeconds -ge $abortAfter -and -not (Test-Path $abortFile)) {
            Set-Content $abortFile "request threads all busy ($busyLimit) or metrics timing out for ${abortAfter}s since $($stuckSince.ToString('HH:mm:ss'))"
        }
        Start-Sleep -Milliseconds 1000
    }
}

function Get-GatlingArgs([string]$project, [string]$dir, [string]$feeder, [string]$tokens, [string]$result) {
    @("-p", $project, "gatlingRun", "-DgatlingReportDir=$dir",
        "--simulation", "com.ticket.loadtest.simulation.$simulation", "-DshowId=$showId",
        "-DcoreBaseUrl=$CoreUrl", "-DbookingFeederFile=$feeder", "-DbookingFeederOffset=0",
        "-DbookingScenario=$bookingScenario", "-DresultFile=$result",
        "-DtechnicalFailureThresholdPercent=$TechnicalFailureLimitPercent", "-DpollingTimeoutSeconds=300", "-DqueueTimeoutThresholdPercent=0",
        "-DmaxCoreAdmissionsPerSecond=0", "-DadmissionRateTolerancePercent=0",
        "-DperformanceId=$PerformanceId", "-Dusers=1", "-DdurationSeconds=$DurationSeconds",
        "-DinjectionMode=constant-users-per-sec", "-DusersPerSecond=$UsersPerSecond", "-DtargetUsersPerSecond=$UsersPerSecond",
        "-DaccessTokenMode=tokens", "-DaccessTokensFile=$tokens", "-DconsoleRunId=$runId")
}
$startedAt = Get-Date
if ($LoadHost) {
    # 원격 명령은 공백이 있어 큰따옴표로 한 인수로 묶는다(Start-Process는 인수를 따옴표 없이 공백으로 잇는다).
    # core.autocrlf로 받은 작업 트리는 gradlew가 CRLF라서 Linux에서 실행되지 않는다(/bin/sh^M). 실행 전에 LF로 바꾼다.
    # 원격 명령은 큰따옴표로 묶여 전달되므로 안에 따옴표를 쓰지 않는다. 안쪽 bash가 \\r을 \r로 풀어 sed에 넘긴다.
    $remoteRun = "timeout $($DurationSeconds + 900)s bash -lc 'cd $RemoteProjectDir && sed -i s/\\r`$// gradlew && ./gradlew " + ((Get-GatlingArgs "load-tests/gatling" $remoteDir "$remoteDir/feeder.csv" "$remoteDir/access-tokens.txt" "$remoteDir/booking-results.csv") -join " ") +
        "; code=`$?; cp load-tests/gatling/build/gatling-jvm.log $remoteDir/ 2>/dev/null; exit `$code'"
    $sshArgs = @($SshOptions | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) + $LoadHost + ('"' + $remoteRun + '"')
    # 중단하면 SSH 클라이언트만 끊어서는 원격 Gatling이 남는다. 실행 폴더 경로가 든 원격 프로세스(gradle, Gatling JVM)를 끝낸다.
    $stopRemote = { & $SshCommand @SshOptions $LoadHost "pkill -f $remoteDir" | Out-Null }
    $gatlingExit = Invoke-Process $SshCommand $sshArgs (Join-Path $runDir "gatling.log") (Join-Path $runDir "gatling.err") $abortFile $stopRemote
} else {
    $runArgs = Get-GatlingArgs $gatlingProject $runDir $feederFile $tokensFile (Join-Path $runDir 'booking-results.csv')
    # Gatling JVM은 Gradle 데몬의 자식이라 띄운 프로세스 트리 밖에 있다. 명령행에 실행 폴더가 든 java를 끝낸다.
    $stopLocal = {
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like "*$runDir*" } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    $gatlingExit = Invoke-Process $gradlew $runArgs (Join-Path $runDir "gatling.log") (Join-Path $runDir "gatling.err") $abortFile $stopLocal
}
New-Item -ItemType File $stopFile | Out-Null
Wait-Job $sampler -Timeout 10 | Out-Null; Remove-Job $sampler -Force
Remove-Item $stopFile -ErrorAction SilentlyContinue
$after = Get-Metrics
Write-Utf8 (Join-Path $runDir "prometheus-after.txt") $after
# 운영 지표는 여기까지 읽는다. 이후는 결과 받아 오기와 이 PC의 분석뿐이다.
Stop-ProdTunnel
$walAfter = Get-Wal
if ($walBefore) {
    Write-Utf8 (Join-Path $runDir "postgres-wal.txt") ("writes,write_time_ms,fsyncs,fsync_time_ms,wal_records,wal_fpi,wal_bytes,xact_commit,track_wal_io_timing`n" + "before,$walBefore`nafter,$walAfter")
}
if ($LoadHost) {
    # 결과를 받아 오고, 토큰이 든 원격 실행 폴더를 지운다. 토큰·피더는 이 PC에 이미 있어 받지 않는다.
    $remoteArchive = "$remoteDir.tgz"
    & $SshCommand @SshOptions $LoadHost "tar -czf $remoteArchive --exclude=./access-tokens.txt --exclude=./feeder.csv -C $remoteDir .; rm -rf $remoteDir"
    $localArchive = Join-Path $runDir "remote-results.tgz"
    & $ScpCommand @ScpOptions "${LoadHost}:$remoteArchive" $localArchive
    $downloadExit = $LASTEXITCODE
    & $SshCommand @SshOptions $LoadHost "rm -f $remoteArchive"
    if ($downloadExit -ne 0) { Fail "could not download results from $LoadHost (see gatling.log)" }
    & $TarCommand -xzf $localArchive -C $runDir
    Remove-Item $localArchive
} else {
    $gcLog = Join-Path $gatlingProject "build\gatling-jvm.log"
    if (Test-Path $gcLog) { Copy-Item $gcLog (Join-Path $runDir "gatling-jvm.log") }
}

# 자동 중단한 실행은 Gatling이 증거 파일을 쓰기 전에 끝났다. 중단(2)이 아니라 FAIL(1)로 남긴다.
$commitLabel = if ($coreInfo) { "@$($coreInfo.commit)" } elseif ($CoreCommit) { "@$CoreCommit" } else { "@unknown" }
$stepLabel = if ($isRealistic) { "$Label $Scenario $commitLabel" } else { "$Label $commitLabel" }
if (Test-Path $abortFile) {
    $reason = "aborted: " + (Get-Content $abortFile -Raw).Trim()
    $summary = [ordered]@{ runId = $runId; label = $Label; scenario = $Scenario; startedAt = $startedAt.ToString("s"); coreUrl = $CoreUrl
        performanceId = $PerformanceId; usersPerSecond = $UsersPerSecond; durationSeconds = $DurationSeconds; users = $users
        verdict = "FAIL"; invalidReasons = @(); failReasons = @($reason) }
    Write-Utf8 (Join-Path $runDir "summary.json") ($summary | ConvertTo-Json -Depth 4)
    $row = "| {0} | {1} | {2} x {3}s | {4} | - | - | - | - | valid | FAIL (aborted) |" -f $startedAt.ToString("MM-dd HH:mm"), $stepLabel, $UsersPerSecond, $DurationSeconds, $PerformanceId
    Write-Utf8 (Join-Path $runDir "summary.md") $row
    [IO.File]::AppendAllText((Join-Path $repo "distributed-results-join\capacity\steps.md"), $row + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Write-Host ""
    Write-Host "verdict: FAIL"
    Write-Host "  fail: $reason" -ForegroundColor Red
    exit 1
}

# --- 분석 ------------------------------------------------------------------------------------
# Core 쪽 요청별 분포: 실행 전후 누적 버킷의 차이. 버킷 경계라서 "이하" 값이다.
function Get-Buckets([string]$text) {
    $map = @{}
    # uri 라벨에 {performanceId} 같은 중괄호가 있으므로 라벨 구간은 줄 끝의 "} 값"까지 읽는다.
    foreach ($m in [regex]::Matches($text, '(?m)^http_server_requests_seconds_bucket\{(.*)\} ([0-9.eE+-]+)\r?$')) {
        $labels = $m.Groups[1].Value
        $method = [regex]::Match($labels, 'method="([^"]+)"').Groups[1].Value
        $uri = [regex]::Match($labels, 'uri="([^"]+)"').Groups[1].Value
        $le = [regex]::Match($labels, 'le="([^"]+)"').Groups[1].Value
        if ($uri -notmatch "^/api/") { continue }
        $key = "$method $uri"; $bound = if ($le -eq "+Inf") { [double]::PositiveInfinity } else { [double]$le }
        if (-not $map.ContainsKey($key)) { $map[$key] = @{} }
        $map[$key][$bound] = [double]$map[$key][$bound] + [double]$m.Groups[2].Value
    }
    $map
}
$b0 = Get-Buckets $before; $b1 = Get-Buckets $after
$core = [ordered]@{}
foreach ($key in ($b1.Keys | Sort-Object)) {
    $bounds = $b1[$key].Keys | Sort-Object
    $delta = foreach ($bound in $bounds) { [pscustomobject]@{ Le = $bound; N = $b1[$key][$bound] - [double]($(if ($b0.ContainsKey($key)) { $b0[$key][$bound] } else { 0 })) } }
    $total = ($delta | Select-Object -Last 1).N
    if ($total -lt 1) { continue }
    $q = { param($p) $hit = $delta | Where-Object { $_.N -ge $p * $total } | Select-Object -First 1; if ([double]::IsInfinity($hit.Le)) { -1 } else { [Math]::Round($hit.Le * 1000) } }
    $core[$key] = [ordered]@{ count = [int]$total; p50Ms = (& $q 0.5); p95Ms = (& $q 0.95); p99Ms = (& $q 0.99) }
}

# Gatling이 시작도 못 하면 증거 파일이 없다. FAIL(1)과 섞이지 않게 중단(2)으로 끝낸다.
if (-not (Test-Path (Join-Path $runDir "booking-evidence.json"))) { Fail "Gatling produced no booking-evidence.json (exit $gatlingExit, see gatling.log and gatling.err)" }
$evidence = Get-Content (Join-Path $runDir "booking-evidence.json") -Raw | ConvertFrom-Json
$admissions = @(Import-Csv (Join-Path $runDir "booking-admissions.csv") -Header epochSecond, count | Select-Object -Skip 1 | ForEach-Object { [int]$_.count })
$inner = if ($admissions.Count -gt 2) { $admissions[1..($admissions.Count - 2)] } else { $admissions }
$minAdmit = ($inner | Measure-Object -Minimum).Minimum; $maxAdmit = ($inner | Measure-Object -Maximum).Maximum
$samples = @(Import-Csv $metricsFile -Delimiter "`t" | Where-Object { $_.hikari_active -ne "TIMEOUT" -and $_.hikari_active })
$maxPending = ($samples | ForEach-Object { [double]$_.hikari_pending } | Measure-Object -Maximum).Maximum
$maxActiveConn = ($samples | ForEach-Object { [double]$_.hikari_active } | Measure-Object -Maximum).Maximum
$maxBusy = ($samples | ForEach-Object { [double]$_.tomcat_busy } | Measure-Object -Maximum).Maximum
$maxCpu = ($samples | ForEach-Object { [double]$_.process_cpu } | Measure-Object -Maximum).Maximum
# 데워졌는지와 사용자 한 명의 Core CPU. 실행 중 JIT 컴파일이 3초를 넘으면 JIT이 아직 CPU를 써서 숫자가 부풀려진다(capacity-log 10-22, P-005).
$jitSeconds = [Math]::Round(((Get-Gauge $after "jvm_compilation_time_ms_total") - (Get-Gauge $before "jvm_compilation_time_ms_total")) / 1000, 1)
$cpuMsPerUser = [Math]::Round(((Get-Gauge $after "process_cpu_time_ns_total") - (Get-Gauge $before "process_cpu_time_ns_total")) / 1e6 / $users, 1)
$timeouts = @(Get-Content $metricsFile | Select-String "TIMEOUT").Count
$gcMax = 0
if (Test-Path (Join-Path $runDir "gatling-jvm.log")) {
    foreach ($m in [regex]::Matches((Get-Content (Join-Path $runDir "gatling-jvm.log") -Raw), 'Pause [^\r\n]* ([0-9.]+)ms')) { $gcMax = [Math]::Max($gcMax, [double]$m.Groups[1].Value) }
}

# Gatling 쪽 요청별 p95/p99: HTML 리포트 통계 표에서 읽는다(col-10 = 95th, col-11 = 99th pct). 사용자가 실제로 기다린 시간이다.
$requests = [ordered]@{}
$report = Get-ChildItem (Join-Path $runDir "$($simulation.ToLower())-*\index.html") -ErrorAction SilentlyContinue | Select-Object -First 1
if ($report) {
    $html = [IO.File]::ReadAllText($report.FullName, [Text.Encoding]::UTF8)
    foreach ($row in [regex]::Matches($html, '<tr[^>]*>(?:(?!</tr>).)*?</tr>', 'Singleline')) {
        $name = [regex]::Match($row.Value, 'class="ellipsed-name">([^<]+)<').Groups[1].Value.Trim()
        $cols = @{}; foreach ($m in [regex]::Matches($row.Value, 'col-(\d+)">([^<]*)<')) { $cols[[int]$m.Groups[1].Value] = $m.Groups[2].Value.Trim().Replace(",", "") }
        if ($name -and $cols[10] -match '^\d+$' -and $cols[11] -match '^\d+$') { $requests[$name] = [ordered]@{ p95Ms = [int]$cols[10]; p99Ms = [int]$cols[11] } }
    }
}

# 밀림: Open Model에서 버틴다는 것은 처리가 입장을 따라간다는 뜻이다. 주입이 끝난 시각(마지막 입장)에 아직 끝나지 않은 사용자 수와
# 마지막 입장 구간의 체류 중앙값을 본다. 30초 안에 응답 기준을 넘지 않아도 밀림이 쌓이면 더 긴 실행에서 무너진다(capacity-log 10-17).
$results = @(Import-Csv (Join-Path $runDir "booking-results.csv") | Where-Object { $_.coreAdmittedAt })
$admittedAt = @($results | ForEach-Object { [datetime]$_.coreAdmittedAt })
$injectionEnd = ($admittedAt | Measure-Object -Maximum).Maximum
$leftAtEnd = @($results | Where-Object { $_.flowCompletedAt -and [datetime]$_.flowCompletedAt -gt $injectionEnd }).Count
$tail = @($results | Where-Object { [datetime]$_.coreAdmittedAt -ge $injectionEnd.AddSeconds(-$TailWindowSeconds) -and $_.coreResidenceMillis } |
    ForEach-Object { [int]$_.coreResidenceMillis } | Sort-Object)
$tailMedian = if ($tail.Count -gt 0) { $tail[[int][Math]::Floor($tail.Count / 2)] } else { $null }

# 유효성: 부하 발생기가 목표 속도로 사람을 넣었는가. Core가 막히면 입장은 일정하고 완료만 흔들린다.
$invalid = @()
# 낮은 부하에서는 초 경계 때문에 1~2명 흔들리는 것이 정상이라 ±2명까지는 허용한다.
$tolerance = [Math]::Max($UsersPerSecond * $AdmissionTolerancePercent / 100, 2)
if ($minAdmit -lt $UsersPerSecond - $tolerance -or $maxAdmit -gt $UsersPerSecond + $tolerance) {
    $invalid += "admissions/s ranged $minAdmit..$maxAdmit (target $UsersPerSecond +-$tolerance): load generator did not inject steadily"
}
if ($gcMax -gt 200) { $invalid += "load generator GC pause ${gcMax}ms" }
if ($evidence.startedUsers -ne $users) { $invalid += "started $($evidence.startedUsers) of $users users" }
if ($coreWarnings.Count -gt 0) { $invalid += $coreWarnings }
# 비즈니스 거절은 회차를 다시 썼거나 좌석·회원 준비가 덜 됐다는 뜻이다. Core 용량과 상관이 없어 숫자를 쓰지 않는다.
$rejections = [ordered]@{}
foreach ($g in @($results | Where-Object { $_.result -like "BUSINESS_REJECTED_*" -or $_.result -like "USER_DROPPED_*" } | Group-Object result | Sort-Object Count -Descending)) { $rejections[$g.Name] = $g.Count }
if ($isRealistic) {
    # 이선좌·매진은 인기 공연 오픈에서 정상이다. 그 밖의 거절만 무효로 본다.
    $unexpected = @($rejections.Keys | Where-Object { $_ -like "BUSINESS_REJECTED_*" -and $ExpectedRejections -notcontains $_ })
    if ($unexpected.Count -gt 0) { $invalid += "unexpected business rejections: " + (($unexpected | ForEach-Object { "$_ $($rejections[$_])" }) -join ", ") + " (member or data problem)" }
} elseif ($evidence.businessRejectedUsers -gt 0) { $invalid += "business rejected $($evidence.businessRejectedUsers) users (data not clean, use a fresh performance)" }
# 기술 실패가 기준을 넘으면 Gatling assertion이 리포트를 만들기 전에 실패로 끝난다. 그때는 리포트가 없어도 기술 실패로 FAIL이다.
$technicalFailed = $evidence.technicalFailurePercent -ge $TechnicalFailureLimitPercent
if (-not $report -and -not $technicalFailed) { $invalid += "Gatling HTML report not found, per-request latency unknown" }
# 03-3의 이탈·취소는 확률로 일어나 짧은 실행에는 없을 수 있다. 03만 요청이 빠지면 무효로 본다.
if (-not $isRealistic) {
    foreach ($name in $LatencyLimits.Keys) { if ($report -and -not $requests.Contains($name) -and -not $technicalFailed) { $invalid += "request '$name' missing from Gatling report" } }
}

$reasons = @()
if ($gatlingExit -ne 0) { $reasons += "gatling exit $($gatlingExit)" }
if ($technicalFailed) { $reasons +="technical failure $($evidence.technicalFailurePercent)% >= $TechnicalFailureLimitPercent%" }
if ($evidence.overloadedUsers -gt 0) { $reasons += "E6003 overloaded $($evidence.overloadedUsers) users" }
foreach ($name in $LatencyLimits.Keys) {
    if (-not $requests.Contains($name)) { continue }
    $limit = $LatencyLimits[$name]; $r = $requests[$name]
    if ($r.p95Ms -gt $limit[0] -or $r.p99Ms -gt $limit[1]) { $reasons += "$name p95 $($r.p95Ms)ms / p99 $($r.p99Ms)ms > $($limit[0]) / $($limit[1])" }
}
$backlogLimit = $UsersPerSecond * $BacklogSeconds
if (-not $isRealistic) {
    if ($leftAtEnd -gt $backlogLimit) { $reasons += "backlog: $leftAtEnd users still in Core when injection ended > $backlogLimit" }
    if ($null -ne $tailMedian -and $tailMedian -gt $TailMedianLimitMillis) { $reasons += "backlog: last ${TailWindowSeconds}s residence median ${tailMedian}ms > $TailMedianLimitMillis" }
}

# 1분 구간 응답 시간(Gatling RequestLatencyRecorder). 실행 전체로만 보면 끝 몇 분의 무너짐이 앞부분에 묻힌다.
$minuteFailures = @()
$minutesFile = Join-Path $runDir "request-minutes.csv"
if ($DurationSeconds -ge 120 -and (Test-Path $minutesFile)) {
    foreach ($m in Import-Csv $minutesFile) {
        if (-not $LatencyLimits.Contains($m.request) -or [int]$m.count -lt $MinuteMinCount) { continue }
        $limit = $LatencyLimits[$m.request]
        if ([int]$m.p95Ms -gt $limit[0] -or [int]$m.p99Ms -gt $limit[1]) {
            $minute = [DateTimeOffset]::FromUnixTimeSeconds([long]$m.epochMinute * 60).LocalDateTime.ToString("HH:mm")
            $minuteFailures += "$minute $($m.request) p95 $($m.p95Ms)ms / p99 $($m.p99Ms)ms (n=$($m.count))"
        }
    }
}
if ($minuteFailures.Count -gt 0) { $reasons += "per-minute latency over limit in $($minuteFailures.Count) minute(s), first: $($minuteFailures[0])" }

# 중복 판매(03-3). 주문의 끝 시각: 확정은 끝이 없고, 취소는 취소 시각, 그 밖(대기·만료)은 만료 시각이다.
# Core는 created_at을 JVM 시간대(운영 컨테이너 UTC)로, expires_at·canceled_at을 서울 시계로 쓴다(2026-11-05 확인).
# 선점 시간은 1시간보다 짧으므로 expires_at - created_at의 시간 단위 차이를 created_at에 더해 같은 시계로 맞춘다.
$duplicateSales = $null
if ($isRealistic) {
    $line = if ($orderBaseline) {
        Invoke-BookingSql ("with s as (select os.seat_id, o.id, o.created_at + floor(extract(epoch from o.expires_at - o.created_at) / 3600) * interval '1 hour' as created_at, " +
            "case when o.status = 'CONFIRMED' then 'infinity'::timestamp " +
            "when o.status = 'CANCELED' then least(o.canceled_at, o.expires_at) else o.expires_at end as ends_at " +
            "from order_seats os join orders o on o.id = os.order_id " +
            "where os.id > $($orderBaseline[1]) and o.id > $($orderBaseline[0]) and o.performance_id = $PerformanceId) " +
            "select (select count(*) from s a join s b on a.seat_id = b.seat_id and a.id < b.id and b.created_at < a.ends_at and a.created_at < b.ends_at), " +
            "(select count(distinct id) from s), (select count(distinct seat_id) from s)")
    }
    if ($line -match '^(\d+),(\d+),(\d+)$') {
        $duplicateSales = [ordered]@{ overlappingOrderPairs = [long]$Matches[1]; orders = [long]$Matches[2]; seats = [long]$Matches[3] }
        if ($duplicateSales.overlappingOrderPairs -gt 0) { $reasons += "duplicate sale: $($duplicateSales.overlappingOrderPairs) order pairs held the same seat at the same time" }
    } else {
        $invalid += "duplicate sale check unavailable (needs compose PostgreSQL or -Prod)"
    }
}
$verdict = if ($invalid.Count -gt 0) { "INVALID" } elseif ($reasons.Count -gt 0) { "FAIL" } else { "PASS" }

$wal = $null
if ($walBefore -and $walAfter) {
    $w0 = $walBefore -split ","; $w1 = $walAfter -split ","
    $d = { param($i) [double]$w1[$i] - [double]$w0[$i] }
    $avg = { param($time, $count) if ($count -gt 0) { [Math]::Round($time / $count, 3) } else { $null } }
    $wal = [ordered]@{ timing = $w1[8]; writes = & $d 0; writeAvgMs = & $avg (& $d 1) (& $d 0); fsyncs = & $d 2; fsyncAvgMs = & $avg (& $d 3) (& $d 2)
        records = & $d 4; fullPageImages = & $d 5; megabytes = [Math]::Round((& $d 6) / 1MB, 1); commits = & $d 7 }
}

$summary = [ordered]@{
    runId = $runId; label = $Label; scenario = $Scenario; startedAt = $startedAt.ToString("s"); coreUrl = $CoreUrl; performanceId = $PerformanceId
    usersPerSecond = $UsersPerSecond; durationSeconds = $DurationSeconds; users = $users
    conditions = [ordered]@{ hikariMax = $hikariMax; tomcatMaxThreads = $tomcatMax; ordersBefore = $ordersBefore; seats = $seats.Count
        core = $coreInfo; coreCommit = $CoreCommit; coreWarnings = $coreWarnings; database = $databaseInfo
        metricsUrl = $MetricsUrl; loadGenerator = $(if ($LoadHost) { $LoadHost } else { "local" }) }
    verdict = $verdict; invalidReasons = $invalid; failReasons = $reasons
    client = [ordered]@{ successful = $evidence.successfulUsers; started = $evidence.startedUsers; technicalFailurePercent = $evidence.technicalFailurePercent
        overloaded = $evidence.overloadedUsers; residenceP95Ms = $evidence.p95CoreResidenceMillis; residenceP99Ms = $evidence.p99CoreResidenceMillis
        maxActiveUsers = $evidence.maxObservedActiveUsers; admissionsPerSecondMin = $minAdmit; admissionsPerSecondMax = $maxAdmit; gatlingGcMaxPauseMs = $gcMax
        requests = $requests; leftAtInjectionEnd = $leftAtEnd; tailResidenceMedianMs = $tailMedian
        rejections = $rejections; minuteFailures = @($minuteFailures | Select-Object -First 20); duplicateSales = $duplicateSales }
    server = [ordered]@{ hikariActiveMax = $maxActiveConn; hikariPendingMax = $maxPending; tomcatBusyMax = $maxBusy; processCpuMax = $maxCpu; jitCompileSeconds = $jitSeconds; coreCpuMsPerUser = $cpuMsPerUser
        metricTimeouts = $timeouts; postgresWal = $wal }
    coreRequests = $core
}
Write-Utf8 (Join-Path $runDir "summary.json") ($summary | ConvertTo-Json -Depth 6)

# 표에 들어갈 짧은 이름. 03이 부르는 다섯 요청이다.
$shortNames = [ordered]@{
    "GET /api/v1/performances/{performanceId}/summary" = "summary"; "GET /api/v1/performances/{performanceId}/seats/status" = "seats"
    "GET /api/v1/performances/{performanceId}/seats/occupied" = "seats"
    "POST /api/v1/performances/{performanceId}/seats/{seatId}/select" = "select"; "POST /api/v1/orders" = "order"
    "GET /api/v1/orders/{orderKey}/status" = "orderStatus"
}
$coreLine = ($shortNames.Keys | Where-Object { $core.Contains($_) } | ForEach-Object { "$($shortNames[$_]) $($core[$_].p95Ms)" }) -join ", "
$row = "| {0} | {1} | {2} x {3}s | {4} | {5}/{6} | {7} / {8} | {9} | {10}/{11} pend {12}, busy {13}/{14} | {15} | {16} |" -f `
    $startedAt.ToString("MM-dd HH:mm"), $stepLabel, $UsersPerSecond, $DurationSeconds, $PerformanceId, $evidence.successfulUsers, $evidence.startedUsers,
    $evidence.p95CoreResidenceMillis, $evidence.p99CoreResidenceMillis, $coreLine, $maxActiveConn, $hikariMax, $maxPending, $maxBusy, $tomcatMax,
    $(if ($invalid.Count -gt 0) { "invalid" } else { "valid" }), $verdict
Write-Utf8 (Join-Path $runDir "summary.md") $row
[IO.File]::AppendAllText((Join-Path $repo "distributed-results-join\capacity\steps.md"), $row + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "verdict: $verdict"
foreach ($r in $invalid) { Write-Host "  invalid: $r" -ForegroundColor Yellow }
foreach ($r in $reasons) { Write-Host "  fail: $r" -ForegroundColor Red }
Write-Host ("client: {0}/{1} ok, residence p95 {2}ms p99 {3}ms, admissions/s {4}..{5}, gatling GC max {6}ms" -f $evidence.successfulUsers, $evidence.startedUsers, $evidence.p95CoreResidenceMillis, $evidence.p99CoreResidenceMillis, $minAdmit, $maxAdmit, $gcMax)
if (-not $isRealistic) { Write-Host ("backlog: {0} users left at injection end (limit {1}), last {2}s residence median {3}ms (limit {4})" -f $leftAtEnd, $backlogLimit, $TailWindowSeconds, $tailMedian, $TailMedianLimitMillis) }
if ($rejections.Count -gt 0) { Write-Host ("rejections: " + (($rejections.Keys | ForEach-Object { "$_ $($rejections[$_])" }) -join ", ")) }
if ($duplicateSales) { Write-Host ("duplicate sale: {0} overlapping order pairs, {1} orders on {2} seats" -f $duplicateSales.overlappingOrderPairs, $duplicateSales.orders, $duplicateSales.seats) }
if ($minuteFailures.Count -gt 0) { Write-Host ("per-minute: " + (($minuteFailures | Select-Object -First 5) -join "; ")) }
foreach ($name in $requests.Keys) { Write-Host ("  gatling {0}: p95 {1} p99 {2} ms" -f $name, $requests[$name].p95Ms, $requests[$name].p99Ms) }
Write-Host ("server: hikari {0}/{1} (pending max {2}), tomcat busy max {3}/{4}, cpu max {5}, orders before {6}" -f $maxActiveConn, $hikariMax, $maxPending, $maxBusy, $tomcatMax, $maxCpu, $ordersBefore)
Write-Host ("jvm: jit {0}s, core cpu {1}ms/user" -f $jitSeconds, $cpuMsPerUser)
if ($jitSeconds -gt 3) { Write-Host "  note: JIT compiled ${jitSeconds}s during this run; the JVM is not warm yet (warm up again before judging)" -ForegroundColor Yellow }
if ($wal) { Write-Host ("postgres wal: fsyncs {0} avg {1}ms, writes {2} avg {3}ms, {4}MB, full-page images {5}, commits {6} (timing {7})" -f $wal.fsyncs, $wal.fsyncAvgMs, $wal.writes, $wal.writeAvgMs, $wal.megabytes, $wal.fullPageImages, $wal.commits, $wal.timing) }
foreach ($key in $core.Keys) { Write-Host ("  core {0}: n={1} p50<={2} p95<={3} p99<={4} ms" -f $key, $core[$key].count, $core[$key].p50Ms, $core[$key].p95Ms, $core[$key].p99Ms) }
Write-Host $row
if ($verdict -eq "PASS") { exit 0 } elseif ($verdict -eq "FAIL") { exit 1 } else { exit 3 }
