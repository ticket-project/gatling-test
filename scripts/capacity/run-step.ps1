param(
    [Parameter(Mandatory = $true)][double]$UsersPerSecond,
    [Parameter(Mandatory = $true)][long]$PerformanceId,
    [int]$DurationSeconds = 30,
    [string]$CoreUrl = "http://localhost:8080",
    [string]$Label = "step",
    [string]$MemberIdsFile = "",
    [string]$JwtSecret = $env:LOADTEST_JWT_SECRET,
    [double]$AdmissionTolerancePercent = 15,
    [int]$ResidenceP99LimitMillis = 5000,
    [int]$ThreadDumps = 0,
    [switch]$OperationalConfirmation
)

# 03 고정 조건(CoreAdmissionCapacitySimulation) 한 단계를 콘솔 없이 실행하고 판정한다.
# 판정 근거는 Gatling 쪽 시간이 아니라 Core 쪽 시간(/actuator/prometheus 히스토그램의 실행 전후 차이)이다.
# 같은 PC의 부하 발생기가 멈추면 Gatling 쪽 시간이 부풀려지므로(capacity-log P-002),
# 초당 실제 입장 수가 목표에서 벗어난 실행은 무효로 표시한다.
# 메시지는 영어로 쓴다. Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트의 한글 문자열을 깨뜨린다.

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlew = Join-Path $repo "gradlew.bat"
$gatlingProject = Join-Path $repo "load-tests\gatling"
$localSecret = "0123456789abcdef0123456789abcdef"
# 부하 픽스처는 공연·회차·좌석이 모두 idBase + n이다(ticket seed LoadTestFixtureSeeder).
# 표준 910000001~(2,000석), 대형 920000001~(15,000석). 좌석은 idBase + 1부터 연속이다.
$seatStartId = [long]([Math]::Floor($PerformanceId / 10000000) * 10000000 + 1)

function Fail([string]$message) { Write-Host "ABORT: $message" -ForegroundColor Red; exit 2 }
# Start-Process -Wait는 자식 프로세스까지 기다린다. gradlew가 새로 띄운 Gradle 데몬은 계속 살아 있으므로 끝나지 않는다.
# 띄운 프로세스만 기다린다. Handle을 먼저 읽어야 Windows PowerShell 5.1에서 ExitCode가 남는다.
function Invoke-Process([string]$file, [string[]]$arguments, [string]$out, [string]$err) {
    $p = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $repo -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle
    $p.WaitForExit()
    $p.ExitCode
}
# actuator 응답은 content type이 text가 아니라서 .Content가 바이트 배열로 온다. 항상 UTF-8로 읽는다.
function Get-Text([string]$url, [hashtable]$headers = @{}) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $headers -TimeoutSec 10
    [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
}

# --- 대상과 입력 확인 ----------------------------------------------------------------------------
$coreUri = [Uri]$CoreUrl
$isLocal = @("localhost", "127.0.0.1", "::1") -contains $coreUri.Host
if (-not $isLocal -and -not $OperationalConfirmation) { Fail "non-local target requires -OperationalConfirmation" }
if (-not $JwtSecret) {
    if ($isLocal) { $JwtSecret = $localSecret } else { Fail "set -JwtSecret or LOADTEST_JWT_SECRET for a non-local target" }
}
if (-not $isLocal -and -not $MemberIdsFile) { Fail "non-local target requires -MemberIdsFile (real ACTIVE member ids)" }
$users = [int][Math]::Ceiling($UsersPerSecond * $DurationSeconds)

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $repo ("distributed-results-join\capacity\{0}-{1}-{2}ups-{3}s" -f $stamp, $Label, $UsersPerSecond, $DurationSeconds)
New-Item -ItemType Directory -Force $runDir | Out-Null
$runId = [guid]::NewGuid().ToString()
Write-Host "run dir: $runDir"

$health = Get-Text "$CoreUrl/actuator/health"
if ($health -notmatch '"status":"UP"') { Fail "Core health is not UP: $health" }
try { Get-Text "$CoreUrl/api/v1/performances/$PerformanceId/summary" | Out-Null } catch { Fail "performance $PerformanceId not found: $($_.Exception.Message)" }

# 로컬 Core가 측정에 맞지 않게 떠 있으면 알린다(capacity-log P-006).
# reset-local.ps1로 띄운 Core라면 core-local.json에 commit·풀·seed 조건이 있다.
$coreWarnings = @()
$coreInfo = $null
if ($isLocal) {
    $listener = Get-NetTCPConnection -LocalPort $coreUri.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)").CommandLine
        if ($cmd -match "jdwp") { $coreWarnings += "Core runs with a debugger agent (jdwp)" }
        if ($cmd -match "TieredStopAtLevel=1") { $coreWarnings += "Core runs with TieredStopAtLevel=1 (C2 compiler off)" }
        $infoFile = Join-Path $repo "distributed-results-join\capacity\core-local.json"
        if (Test-Path $infoFile) { $coreInfo = Get-Content $infoFile -Raw | ConvertFrom-Json }
        if (-not $coreInfo -or $coreInfo.pid -ne $listener.OwningProcess) {
            $coreWarnings += "Core was not started by reset-local.ps1, so commit and conditions are unknown"
            $coreInfo = $null
        }
    }
}

# --- 토큰과 피더 생성, 회차 오염 검사 --------------------------------------------------------------
$tokensFile = Join-Path $runDir "access-tokens.txt"
$feederFile = Join-Path $runDir "feeder.csv"
$genArgs = @("-p", $gatlingProject, "generateAccessTokens", "-q",
    "-Doutput=$tokensFile", "-DtokenCount=$users", "-DjwtSecret=$JwtSecret", "-DjwtIssuer=ticket",
    "-DsyntheticMemberStartId=1", "-DsyntheticJwtRole=MEMBER", "-DsyntheticTokenTtlSeconds=3600",
    "-DbookingFeederOutput=$feederFile", "-DbookingSeatStartId=$seatStartId")
if ($MemberIdsFile) { $genArgs += "-DmemberIdsFile=$((Resolve-Path $MemberIdsFile).Path)" }
$genExit = Invoke-Process $gradlew $genArgs (Join-Path $runDir "token-generation.log") (Join-Path $runDir "token-generation.err")
if ($genExit -ne 0) { Fail "token generation failed (see token-generation.err)" }

# 한 번 쓴 회차는 좌석이 선택·선점돼 있어 결과가 섞인다. 실행 전에 전부 AVAILABLE인지 본다.
$firstToken = (Get-Content $tokensFile -TotalCount 1).Trim()
$seats = (ConvertFrom-Json (Get-Text "$CoreUrl/api/v1/performances/$PerformanceId/seats/status" @{ Authorization = "Bearer $firstToken" })).data.seats
$available = @($seats | Where-Object { $_.status -eq "AVAILABLE" }).Count
if ($available -ne $seats.Count -or $available -lt $users) {
    Fail "performance $PerformanceId is not clean or too small: available=$available of $($seats.Count), need $users. Use a fresh or larger performance."
}
# 피더는 seatStartId부터 연속 좌석을 쓴다. 그 가정이 이 회차에서 맞는지 응답으로 확인한다.
$seatIds = $seats | ForEach-Object { [long]$_.seatId } | Sort-Object
if ($seatIds[0] -ne $seatStartId -or $seatIds[-1] - $seatIds[0] + 1 -ne $seatIds.Count) {
    Fail "seat ids of performance $PerformanceId are $($seatIds[0])..$($seatIds[-1]) ($($seatIds.Count) seats), not consecutive from $seatStartId"
}

# --- 실행 전 상태 ------------------------------------------------------------------------------
function Get-Metrics { Get-Text "$CoreUrl/actuator/prometheus" }
function Get-Gauge([string]$text, [string]$name) {
    $m = [regex]::Match($text, "(?m)^$name\{[^}]*\} ([0-9.eE+-]+)")
    if ($m.Success) { [double]$m.Groups[1].Value } else { $null }
}
$before = Get-Metrics
# 원문을 남겨 두면 연결 점유 시간, 저장소 메서드별 호출, Core GC 같은 다른 지표도 실행 뒤에 전후 차이로 볼 수 있다.
$before | Set-Content (Join-Path $runDir "prometheus-before.txt") -Encoding UTF8
$hikariMax = Get-Gauge $before "hikaricp_connections_max"
$tomcatMax = Get-Gauge $before "tomcat_threads_config_max_threads"

# 로컬 H2라면 주문 행 수를 남긴다. 주문 존재 확인이 전체 스캔이라 행 수가 결과에 영향을 줄 수 있다(P-001).
# Core와 같은 H2 jar(Spring Boot가 관리하는 최신 2.x)를 쓴다. 1.4는 2.x 파일을 못 연다.
# 빈 비밀번호는 PowerShell 5.1이 인자에서 지우므로 인자를 문자열 하나로 넘긴다. 멈추면 20초 뒤 포기한다.
# Start-Process -PassThru의 ExitCode는 5.1에서 비어 오므로 출력으로만 판단한다.
$ordersBefore = $null
if ($isLocal) {
    $h2 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.h2database\h2" -Recurse -Filter "h2-2*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "sources" } | Sort-Object Name | Select-Object -Last 1
    if ($h2) {
        $h2Out = Join-Path $runDir "orders-before.txt"
        $h2Args = "-cp `"$($h2.FullName)`" org.h2.tools.Shell -url `"jdbc:h2:file:~/ticket-local;MODE=Oracle;AUTO_SERVER=TRUE`" -user sa -password `"`" -sql `"SELECT COUNT(*) FROM ORDERS`""
        $h2Proc = Start-Process -FilePath "java" -ArgumentList $h2Args -NoNewWindow -PassThru -RedirectStandardOutput $h2Out -RedirectStandardError "$h2Out.err"
        if ($h2Proc.WaitForExit(20000)) {
            $lines = @(Get-Content $h2Out)
            if ($lines.Count -ge 2 -and $lines[1].Trim() -match '^\d+$') { $ordersBefore = [long]$lines[1].Trim() }
        } else { Stop-Process -Id $h2Proc.Id -Force }
    }
}

# --- 1초 단위 Core 지표 수집과 Gatling 실행 ----------------------------------------------------------
$metricsFile = Join-Path $runDir "core-metrics.tsv"
$stopFile = Join-Path $runDir ".stop-sampler"
# 진단 실행(-ThreadDumps N): Core가 막히기 시작한 순간부터 3초 간격으로 스레드 덤프 N장을 thread-dump-<n>.txt로 남긴다.
# 덤프하는 동안 Core가 잠깐 멈추므로 진단 실행의 숫자는 판정과 사다리에 쓰지 않는다.
$jcmd = ""; $corePid = 0
if ($ThreadDumps -gt 0) {
    if (-not $coreInfo) { Fail "-ThreadDumps needs a local Core started by reset-local.ps1" }
    $jcmd = Join-Path (Split-Path $coreInfo.java) "jcmd.exe"
    if (-not (Test-Path $jcmd)) { Fail "jcmd not found next to Core java: $jcmd" }
    $corePid = $coreInfo.pid
}
$sampler = Start-Job -ArgumentList $CoreUrl, $metricsFile, $stopFile, $ThreadDumps, $jcmd, $corePid, $runDir -ScriptBlock {
    param($url, $file, $stop, $dumps, $jcmd, $corePid, $dir)
    Set-Content $file "time`thikari_active`thikari_pending`ttomcat_busy`tprocess_cpu`tsystem_cpu"
    $names = "hikaricp_connections_active", "hikaricp_connections_pending", "tomcat_threads_busy_threads", "process_cpu_usage", "system_cpu_usage"
    $taken = 0; $lastDump = [datetime]::MinValue
    while (-not (Test-Path $stop)) {
        $t = Get-Date -Format "HH:mm:ss"
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$url/actuator/prometheus" -TimeoutSec 2
            $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
            $values = foreach ($n in $names) { $m = [regex]::Match($text, "(?m)^$n\{[^}]*\} ([0-9.eE+-]+)"); if ($m.Success) { $m.Groups[1].Value } else { "" } }
            Add-Content $file ($t + "`t" + ($values -join "`t"))
            # ponytail: 고정 문턱(연결 대기 > 0 또는 바쁜 요청 스레드 20개 이상). 문턱 아래에서 FAIL하면 덤프가 안 남으니 그때 매개변수로 뺀다.
            if ($taken -lt $dumps -and ([double]$values[1] -gt 0 -or [double]$values[2] -ge 20) -and ((Get-Date) - $lastDump).TotalSeconds -ge 3) {
                $taken++; $lastDump = Get-Date
                & $jcmd $corePid Thread.print -l | Set-Content (Join-Path $dir "thread-dump-$taken.txt") -Encoding UTF8
            }
        } catch { Add-Content $file "$t`tTIMEOUT" }
        Start-Sleep -Milliseconds 1000
    }
}

$runArgs = @("-p", $gatlingProject, "gatlingRun", "-DgatlingReportDir=$runDir",
    "--simulation", "com.ticket.loadtest.simulation.CoreAdmissionCapacitySimulation",
    "-DcoreBaseUrl=$CoreUrl", "-DbookingFeederFile=$feederFile", "-DbookingFeederOffset=0",
    "-DbookingScenario=CORE_ADMISSION_CAPACITY", "-DresultFile=$(Join-Path $runDir 'booking-results.csv')",
    "-DtechnicalFailureThresholdPercent=1.0", "-DpollingTimeoutSeconds=300", "-DqueueTimeoutThresholdPercent=0",
    "-DmaxCoreAdmissionsPerSecond=0", "-DadmissionRateTolerancePercent=0",
    "-DperformanceId=$PerformanceId", "-Dusers=1", "-DdurationSeconds=$DurationSeconds",
    "-DinjectionMode=constant-users-per-sec", "-DusersPerSecond=$UsersPerSecond", "-DtargetUsersPerSecond=$UsersPerSecond",
    "-DaccessTokenMode=tokens", "-DaccessTokensFile=$tokensFile", "-DconsoleRunId=$runId")
$startedAt = Get-Date
$gatlingExit = Invoke-Process $gradlew $runArgs (Join-Path $runDir "gatling.log") (Join-Path $runDir "gatling.err")
New-Item -ItemType File $stopFile | Out-Null
Wait-Job $sampler -Timeout 10 | Out-Null; Remove-Job $sampler -Force
Remove-Item $stopFile -ErrorAction SilentlyContinue
$after = Get-Metrics
$after | Set-Content (Join-Path $runDir "prometheus-after.txt") -Encoding UTF8
$gcLog = Join-Path $gatlingProject "build\gatling-jvm.log"
if (Test-Path $gcLog) { Copy-Item $gcLog (Join-Path $runDir "gatling-jvm.log") }

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

$evidence = Get-Content (Join-Path $runDir "booking-evidence.json") -Raw | ConvertFrom-Json
$admissions = @(Import-Csv (Join-Path $runDir "booking-admissions.csv") -Header epochSecond, count | Select-Object -Skip 1 | ForEach-Object { [int]$_.count })
$inner = if ($admissions.Count -gt 2) { $admissions[1..($admissions.Count - 2)] } else { $admissions }
$minAdmit = ($inner | Measure-Object -Minimum).Minimum; $maxAdmit = ($inner | Measure-Object -Maximum).Maximum
$samples = @(Import-Csv $metricsFile -Delimiter "`t" | Where-Object { $_.hikari_active -ne "TIMEOUT" -and $_.hikari_active })
$maxPending = ($samples | ForEach-Object { [double]$_.hikari_pending } | Measure-Object -Maximum).Maximum
$maxActiveConn = ($samples | ForEach-Object { [double]$_.hikari_active } | Measure-Object -Maximum).Maximum
$maxBusy = ($samples | ForEach-Object { [double]$_.tomcat_busy } | Measure-Object -Maximum).Maximum
$maxCpu = ($samples | ForEach-Object { [double]$_.process_cpu } | Measure-Object -Maximum).Maximum
$timeouts = @(Get-Content $metricsFile | Select-String "TIMEOUT").Count
$gcMax = 0
if (Test-Path (Join-Path $runDir "gatling-jvm.log")) {
    foreach ($m in [regex]::Matches((Get-Content (Join-Path $runDir "gatling-jvm.log") -Raw), 'Pause [^\r\n]* ([0-9.]+)ms')) { $gcMax = [Math]::Max($gcMax, [double]$m.Groups[1].Value) }
}

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

$reasons = @()
if ($gatlingExit -ne 0) { $reasons += "gatling exit $($gatlingExit)" }
if ($evidence.technicalFailurePercent -ge 1.0) { $reasons += "technical failure $($evidence.technicalFailurePercent)%" }
if ($evidence.overloadedUsers -gt 0) { $reasons += "E6003 overloaded $($evidence.overloadedUsers) users" }
if ($evidence.p99CoreResidenceMillis -gt $ResidenceP99LimitMillis) { $reasons += "residence p99 $($evidence.p99CoreResidenceMillis)ms > $ResidenceP99LimitMillis" }
if ($evidence.businessRejectedUsers -gt 0) { $reasons += "business rejected $($evidence.businessRejectedUsers) users (data not clean)" }
$verdict = if ($invalid.Count -gt 0) { "INVALID" } elseif ($reasons.Count -gt 0) { "FAIL" } else { "PASS" }

$summary = [ordered]@{
    runId = $runId; label = $Label; startedAt = $startedAt.ToString("s"); coreUrl = $CoreUrl; performanceId = $PerformanceId
    usersPerSecond = $UsersPerSecond; durationSeconds = $DurationSeconds; users = $users
    conditions = [ordered]@{ hikariMax = $hikariMax; tomcatMaxThreads = $tomcatMax; ordersBefore = $ordersBefore; seats = $seats.Count
        core = $coreInfo; coreWarnings = $coreWarnings }
    verdict = $verdict; invalidReasons = $invalid; failReasons = $reasons
    client = [ordered]@{ successful = $evidence.successfulUsers; started = $evidence.startedUsers; technicalFailurePercent = $evidence.technicalFailurePercent
        overloaded = $evidence.overloadedUsers; residenceP95Ms = $evidence.p95CoreResidenceMillis; residenceP99Ms = $evidence.p99CoreResidenceMillis
        maxActiveUsers = $evidence.maxObservedActiveUsers; admissionsPerSecondMin = $minAdmit; admissionsPerSecondMax = $maxAdmit; gatlingGcMaxPauseMs = $gcMax }
    server = [ordered]@{ hikariActiveMax = $maxActiveConn; hikariPendingMax = $maxPending; tomcatBusyMax = $maxBusy; processCpuMax = $maxCpu; metricTimeouts = $timeouts }
    coreRequests = $core
}
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDir "summary.json") -Encoding UTF8

# 표에 들어갈 짧은 이름. 03이 부르는 다섯 요청이다.
$shortNames = [ordered]@{
    "GET /api/v1/performances/{performanceId}/summary" = "summary"; "GET /api/v1/performances/{performanceId}/seats/status" = "seats"
    "POST /api/v1/performances/{performanceId}/seats/{seatId}/select" = "select"; "POST /api/v1/orders" = "order"
    "GET /api/v1/orders/{orderKey}/status" = "orderStatus"
}
$coreLine = ($shortNames.Keys | Where-Object { $core.Contains($_) } | ForEach-Object { "$($shortNames[$_]) $($core[$_].p95Ms)" }) -join ", "
$commitLabel = if ($coreInfo) { "@$($coreInfo.commit)" } else { "@unknown" }
$row = "| {0} | {1} | {2} x {3}s | {4} | {5}/{6} | {7} / {8} | {9} | {10}/{11} pend {12}, busy {13}/{14} | {15} | {16} |" -f `
    $startedAt.ToString("MM-dd HH:mm"), "$Label $commitLabel", $UsersPerSecond, $DurationSeconds, $PerformanceId, $evidence.successfulUsers, $evidence.startedUsers,
    $evidence.p95CoreResidenceMillis, $evidence.p99CoreResidenceMillis, $coreLine, $maxActiveConn, $hikariMax, $maxPending, $maxBusy, $tomcatMax,
    $(if ($invalid.Count -gt 0) { "invalid" } else { "valid" }), $verdict
$row | Set-Content (Join-Path $runDir "summary.md") -Encoding UTF8
Add-Content (Join-Path $repo "distributed-results-join\capacity\steps.md") $row -Encoding UTF8

Write-Host ""
Write-Host "verdict: $verdict"
foreach ($r in $invalid) { Write-Host "  invalid: $r" -ForegroundColor Yellow }
foreach ($r in $reasons) { Write-Host "  fail: $r" -ForegroundColor Red }
Write-Host ("client: {0}/{1} ok, residence p95 {2}ms p99 {3}ms, admissions/s {4}..{5}, gatling GC max {6}ms" -f $evidence.successfulUsers, $evidence.startedUsers, $evidence.p95CoreResidenceMillis, $evidence.p99CoreResidenceMillis, $minAdmit, $maxAdmit, $gcMax)
Write-Host ("server: hikari {0}/{1} (pending max {2}), tomcat busy max {3}/{4}, cpu max {5}, orders before {6}" -f $maxActiveConn, $hikariMax, $maxPending, $maxBusy, $tomcatMax, $maxCpu, $ordersBefore)
foreach ($key in $core.Keys) { Write-Host ("  core {0}: n={1} p50<={2} p95<={3} p99<={4} ms" -f $key, $core[$key].count, $core[$key].p50Ms, $core[$key].p95Ms, $core[$key].p99Ms) }
Write-Host $row
if ($verdict -eq "PASS") { exit 0 } elseif ($verdict -eq "FAIL") { exit 1 } else { exit 3 }
