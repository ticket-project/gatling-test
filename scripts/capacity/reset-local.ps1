param(
    [int]$PerformanceCount = 30,
    [int]$LargePerformanceCount = 0,
    [int]$Members = 2000,
    [long]$BackgroundOrders = 0,
    [int]$PoolSize = 0,
    [switch]$KeepData,
    [switch]$Compose,
    [switch]$Datadog,
    [string]$TicketRepo = (Join-Path $PSScriptRoot "..\..\..\ticket"),
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\..\local-env\ticket-core.env"),
    [int]$Port = 8080
)

# 측정용 로컬 Core를 같은 조건으로 다시 띄운다(capacity-log P-006).
# 1) 포트가 비어 있는지 확인한다. 다른 Core가 떠 있으면 끄지 않고 멈춘다.
# 2) 로컬 PostgreSQL에 접속한다. 기존 데이터는 지우지 않는다.
# 3) bootJar로 만든 jar를 java -jar로 띄운다. 운영과 같은 실행 방식이다. bootRun과 IDE 실행은
#    -XX:TieredStopAtLevel=1(C2 꺼짐)을 붙이므로 측정에 쓰지 않는다. SQL 로그(p6spy)도 끈다.
# 4) -KeepData가 없으면 seedLocal로 부하 회차·회원·배경 주문을 적재한다.
# 5) 띄운 Core의 commit과 조건을 distributed-results-join/capacity/core-local.json에 남긴다. run-step.ps1이 읽는다.
# -Compose: Core를 Windows가 아니라 ticket docker-compose-local.yml의 컨테이너로 띄운다. PostgreSQL·Redis와 같은
#    Docker 네트워크라 쿼리가 Docker 포트 중계를 지나지 않는다(capacity-log P-008). 포트·환경 파일은 compose가 정한다.
#    -PoolSize는 이 셸의 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE로 compose에 넘긴다.
# -Datadog(-Compose 전용): compose의 datadog-agent를 함께 띄우고 CORE_JAVA_TOOL_OPTIONS로 Core 트레이서를 켠다.
#    빼고 띄우면 Agent를 지우고 트레이서도 끈다. 트레이서가 Core CPU를 쓰므로 켠 실행은 끈 실행과 따로 비교한다.
# 메시지는 영어로 쓴다(run-step.ps1과 같은 이유).

$ErrorActionPreference = "Stop"
function Fail([string]$message) { Write-Host "ABORT: $message" -ForegroundColor Red; exit 2 }
# Start-Process -Wait는 자식까지 기다려서, gradlew가 새로 띄운 Gradle 데몬 때문에 끝나지 않는다. 띄운 프로세스만 기다린다.
# Handle을 먼저 읽어야 Windows PowerShell 5.1에서 ExitCode가 남는다.
function Invoke-Native([string]$file, [string[]]$arguments) {
    $p = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $TicketRepo -NoNewWindow -PassThru
    $null = $p.Handle
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { Fail "$(Split-Path -Leaf $file) $($arguments -join ' ') failed (exit $($p.ExitCode))" }
}
function Invoke-Gradle([string[]]$arguments) { Invoke-Native (Join-Path $TicketRepo "gradlew.bat") $arguments }
$TicketRepo = (Resolve-Path $TicketRepo).Path
$EnvFile = (Resolve-Path $EnvFile).Path
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$capacityDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path "distributed-results-join\capacity"
New-Item -ItemType Directory -Force $capacityDir | Out-Null
$coreLog = Join-Path $capacityDir "core-local-$stamp.log"

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener -and -not $Compose) {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)").CommandLine
    Fail "port $Port is in use by PID $($listener.OwningProcess). Stop it first. $($cmd.Substring(0, [Math]::Min(160, $cmd.Length)))"
}

Write-Host "building bootJar..."
Invoke-Gradle @("bootJar", "-q")
$jar = Get-ChildItem (Join-Path $TicketRepo "build\libs") -Filter "*.jar" | Where-Object { $_.Name -notmatch "plain" } |
    Sort-Object LastWriteTime | Select-Object -Last 1
$commit = (& git -C $TicketRepo rev-parse --short HEAD).Trim()
$dirty = @(& git -C $TicketRepo status --porcelain -- src build.gradle gradle).Count -gt 0

# env 파일의 KEY=VALUE를 이 프로세스 환경에 넣으면 Start-Process로 띄운 Core가 물려받는다.
foreach ($line in Get-Content -Encoding UTF8 $EnvFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Set-Item "env:$($Matches[1])" $Matches[2] }
}
$databaseUrl = if ($env:SPRING_DATASOURCE_URL) { $env:SPRING_DATASOURCE_URL } else { "jdbc:postgresql://localhost:5432/ticket" }
if ($databaseUrl -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1|\[::1\])[:/]') { Fail "capacity startup requires a local PostgreSQL URL" }
$databaseUri = [Uri]$databaseUrl.Substring(5)
$seedArgs = @("seedLocal", "-q",
    "-Dseed.load-test-fixture.performance-count=$PerformanceCount",
    "-Dseed.load-test-fixture.large-performance-count=$LargePerformanceCount",
    "-Dseed.load-test-members.count=$Members",
    "-Dseed.background-orders.count=$BackgroundOrders")
# -KeepData는 적재하지 않으므로 seed 조건을 추측하지 않는다. 실제 행 수는 run-step.ps1이 DB에서 읽는다.
$seed = if ($KeepData) { $null } else { [ordered]@{ performances = $PerformanceCount; largePerformances = $LargePerformanceCount; members = $Members; backgroundOrders = $BackgroundOrders } }
$workerEnabled = if ($env:WORKER_ENABLED) { $env:WORKER_ENABLED } else { "true" }
$infoFile = Join-Path $capacityDir "core-local.json"

if ($Datadog -and -not $Compose) { Fail "-Datadog needs -Compose" }
if ($Compose) {
    if ($env:SPRING_DATASOURCE_URL) { Fail "-Compose uses the compose PostgreSQL; remove SPRING_DATASOURCE_URL from $EnvFile" }
    if ($PoolSize -gt 0) { $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "$PoolSize" } else { Remove-Item env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE -ErrorAction SilentlyContinue }
    $composeArgs = @("compose", "-f", (Join-Path $TicketRepo "docker-compose-local.yml"))
    $services = @("core")
    if ($Datadog) {
        $ddEnv = Join-Path (Split-Path $EnvFile) "datadog.env"
        if (-not ((Test-Path $ddEnv) -and (Select-String -Quiet -Path $ddEnv -Pattern '^DD_API_KEY=.+'))) { Fail "-Datadog needs DD_API_KEY and DD_SITE in $ddEnv" }
        $env:CORE_JAVA_TOOL_OPTIONS = "-javaagent:/opt/datadog/dd-java-agent.jar"
        $services += "datadog-agent"
    } else {
        Remove-Item env:CORE_JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
        Invoke-Native "docker" ($composeArgs + @("rm", "-s", "-f", "datadog-agent"))
    }
    # 새 DB면 Core가 기동하며 Flyway로 스키마를 만든다. seed는 그 뒤에 worker 없이(Core를 멈추고) 넣는다.
    Invoke-Native "docker" ($composeArgs + @("up", "-d", "--build", "--force-recreate", "--wait") + $services)
    if (-not $KeepData) {
        Invoke-Native "docker" ($composeArgs + @("stop", "core"))
        Write-Host "seeding (this can take long with large fixtures or background orders)..."
        Invoke-Gradle $seedArgs
        Invoke-Native "docker" ($composeArgs + @("up", "-d", "--wait", "core"))
    }
    $published = [int](((& docker @composeArgs port core 8080) -split ':')[-1])
    [ordered]@{
        startedAt = (Get-Date).ToString("s"); container = "ticket-core"; image = (& docker inspect -f "{{.Image}}" ticket-core).Trim()
        commit = $commit; sourceDirty = $dirty; mode = "docker compose"; p6spy = "off"; hikariPool = $(if ($PoolSize -gt 0) { $PoolSize } else { "default" }); datadog = [bool]$Datadog
        dataReset = $false; seedApplied = (-not $KeepData); port = $published; database = "PostgreSQL"
        databaseTarget = $databaseUri.GetLeftPart([UriPartial]::Path); envFile = $EnvFile
        redisDatabase = $env:SPRING_DATA_REDIS_DATABASE; workerEnabled = $workerEnabled; seed = $seed
    } | ConvertTo-Json -Depth 3 | ForEach-Object { [IO.File]::WriteAllText($infoFile, $_, [Text.UTF8Encoding]::new($false)) }
    Write-Host "ready: commit $commit$(if ($dirty) { ' (dirty)' }), container ticket-core on port $published. Warm up with one run before measuring (P-005)."
    Write-Host "stop: docker $($composeArgs -join ' ') stop core"
    exit 0
}
$env:SERVER_PORT = "$Port"
$env:SPRING_PROFILES_ACTIVE = "local"
$env:DECORATOR_DATASOURCE_ENABLED = "false"
if ($PoolSize -gt 0) { $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "$PoolSize" } else { Remove-Item env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE -ErrorAction SilentlyContinue }

# Core는 Java 25로 빌드된다(ticket build.gradle toolchain). PATH의 java(JAVA_HOME)는 다른 버전일 수 있어
# ~/.jdks에서 가장 높은 25.x를 고른다. Gradle이 툴체인으로 받은 JDK와 같은 곳이다.
$java = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "*25*" -ErrorAction SilentlyContinue | Sort-Object Name |
    Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
if (-not $java -or -not (Test-Path $java)) { Fail "JDK 25 not found under $env:USERPROFILE\.jdks" }
function Start-Core([string]$log) {
    $process = Start-Process -FilePath $java -ArgumentList "-jar", "`"$($jar.FullName)`"" -WorkingDirectory $TicketRepo -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    Write-Host "core starting (PID $($process.Id)), log: $log"
    $deadline = (Get-Date).AddSeconds(240)
    do {
        Start-Sleep -Seconds 3
        try { $health = (Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/actuator/health" -TimeoutSec 3).StatusCode } catch { $health = 0 }
    } while ($health -ne 200 -and (Get-Date) -lt $deadline -and -not $process.HasExited)
    if ($health -ne 200) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id }
        Fail "core did not become healthy (see $log)"
    }
    Write-Host "core is UP"
    return $process
}
# seed 중에는 worker를 끄고, 적재가 끝나면 원래 설정으로 다시 띄운다.
if (-not $KeepData) { $env:WORKER_ENABLED = "false" }
$core = Start-Core $coreLog

if (-not $KeepData) {
    Write-Host "seeding (this can take long with large fixtures or background orders)..."
    Invoke-Gradle $seedArgs
    Stop-Process -Id $core.Id
    $core.WaitForExit()
    $env:WORKER_ENABLED = $workerEnabled
    $coreLog = Join-Path $capacityDir "core-local-$stamp-measurement.log"
    $core = Start-Core $coreLog
}

$pool = if ($PoolSize -gt 0) { $PoolSize } else { "default" }
[ordered]@{
    startedAt = (Get-Date).ToString("s"); pid = $core.Id; jar = $jar.Name; java = $java; commit = $commit; sourceDirty = $dirty
    mode = "java -jar"; p6spy = "off"; hikariPool = $pool; dataReset = $false; seedApplied = (-not $KeepData)
    port = $Port; database = "PostgreSQL"; databaseTarget = $databaseUri.GetLeftPart([UriPartial]::Path); envFile = $EnvFile
    redisDatabase = $env:SPRING_DATA_REDIS_DATABASE; workerEnabled = $workerEnabled
    seed = $seed
} | ConvertTo-Json -Depth 3 | ForEach-Object { [IO.File]::WriteAllText($infoFile, $_, [Text.UTF8Encoding]::new($false)) }
Write-Host "ready: commit $commit$(if ($dirty) { ' (dirty)' }), Hikari pool $pool, PID $($core.Id). Warm up with one run before measuring (P-005)."
Write-Host "stop: Stop-Process -Id $($core.Id)"
