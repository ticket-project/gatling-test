param(
    [string]$KeyPath = "",
    [string[]]$Hosts = @(
        "ubuntu@43.203.155.15",
        "ubuntu@15.165.40.25",
        "ubuntu@43.203.136.184"
    ),
    [string]$RemoteProjectDir = "~/gatling-test",
    [string]$ConsoleRunId = "",
    [string]$RunDescription = "",
    [string]$BaseUrl = "",
    [string]$Simulation = "com.ticket.loadtest.simulation.CdnPublicStateSimulation",
    [int]$PerformanceId = 1,
    [int]$RpsPerNode = 100,
    [int]$DurationSeconds = 300,
    [string]$InjectionMode = "constant-users-per-sec",
    [long]$StartAtEpochMillis = 0,
    [int]$StatusPolls = 1,
    [int]$StatusPollPauseSeconds = 0,
    [int]$StatusPollPauseJitterSeconds = 0,
    [switch]$EnableHttp2,
    [string]$AccessTokenMode = "",
    [string]$AccessTokensFile = "",
    [switch]$GenerateAccessTokens,
    [int]$TokenCountPerNode = 0,
    [string]$JwtSecret = "",
    [string]$JwtIssuer = "ticket",
    [long]$SyntheticMemberStartId = 1,
    [string]$SyntheticJwtRole = "MEMBER",
    [int]$SyntheticTokenTtlSeconds = 3600,
    [switch]$DumpFailureBody,
    [int]$DumpFailureBodyLimit = 1,
    [switch]$SyncProject,
    [switch]$SkipPreflight,
    [switch]$IncludeLocal,
    [switch]$CollectReports,
    [string]$LocalProjectDir = (Join-Path $PSScriptRoot "."),
    [string]$ReportRoot = (Join-Path $PSScriptRoot "distributed-results-join")
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    throw "BaseUrl must be provided explicitly"
}
$targetUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$targetUri) `
        -or $targetUri.Scheme -notin @("http", "https") `
        -or [string]::IsNullOrWhiteSpace($targetUri.Host)) {
    throw "BaseUrl must be an absolute HTTP(S) URL: $BaseUrl"
}

. (Join-Path $PSScriptRoot 'scripts/distributed-common.ps1')

function Get-SimulationRunName {
    if ($Simulation -like "*QueueJoinOnly*") {
        return "join"
    }
    if ($Simulation -like "*CdnPublicState*") {
        return "cdn"
    }
    return (($Simulation -split "\.")[-1] -replace "Simulation$", "").ToLowerInvariant()
}

function Get-BaseUrlRunName {
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
        return ""
    }

    $target = $BaseUrl.Trim()
    $uri = $null
    if ([Uri]::TryCreate($target, [UriKind]::Absolute, [ref]$uri) -and -not [string]::IsNullOrWhiteSpace($uri.Host)) {
        $path = $uri.AbsolutePath.Trim("/")
        if ([string]::IsNullOrWhiteSpace($path)) {
            return "api-$($uri.Host.ToLowerInvariant())"
        }
        return "api-$($uri.Host.ToLowerInvariant())-$($path -replace '/', '-')"
    }

    return "api-$target"
}

function New-RunDirectoryName {
    $nodeCount = $Hosts.Count + [int]$IncludeLocal.IsPresent
    $parts = @(
        (Get-SimulationRunName),
        (Get-BaseUrlRunName),
        "pid$PerformanceId",
        "rps$RpsPerNode",
        "nodes$nodeCount",
        "dur${DurationSeconds}s"
    )
    if ($InjectionMode -ne "constant-users-per-sec") {
        $parts += "profile$InjectionMode"
    }

    if ($GenerateAccessTokens) {
        $parts += "tokens$EffectiveTokenCountPerNode"
    } elseif (-not [string]::IsNullOrWhiteSpace($AccessTokenMode)) {
        $parts += $AccessTokenMode.ToLowerInvariant()
    }

    if ($StatusPolls -gt 0) {
        $pollPart = "poll${StatusPolls}x${StatusPollPauseSeconds}s"
        if ($StatusPollPauseJitterSeconds -gt 0) {
            $pollPart += "_jitter${StatusPollPauseJitterSeconds}s"
        }
        $parts += $pollPart
    }
    if ($IncludeLocal) {
        $parts += "local"
    }
    if ($EnableHttp2) {
        $parts += "h2"
    }
    if ($DumpFailureBody) {
        $parts += "dumpbody"
    }

    $name = ($parts -join "_") -replace "[^A-Za-z0-9._-]", "-"
    return $name.Trim("._-")
}


function Get-ExpectedUsersPerNode {
    if ($InjectionMode -ne "ticket-open") {
        return [Math]::Max(1, [int][Math]::Ceiling($RpsPerNode * $DurationSeconds))
    }
    $firstDecay = $RpsPerNode * 0.5
    $secondDecay = $RpsPerNode * 0.2
    $tail = $RpsPerNode * 0.1
    return [int]([Math]::Ceiling($RpsPerNode * 10) +
        [Math]::Ceiling((($RpsPerNode + $firstDecay) / 2.0) * 20) +
        [Math]::Ceiling((($firstDecay + $secondDecay) / 2.0) * 60) +
        [Math]::Ceiling((($secondDecay + $tail) / 2.0) * 180) + 60)
}

function New-GatlingArgs {
    param(
        [string]$ReportDir = "",
        [string]$NodeAccessTokensFile = "",
        [string]$FailureBodyDir = ""
    )

    $args = @(
        "-p", "load-tests/gatling",
        "gatlingRun",
        "--simulation", $Simulation,
        "-DconsoleRunId=$ConsoleRunId",
        "-DbaseUrl=$BaseUrl",
        "-DperformanceId=$PerformanceId",
        "-Dusers=1",
        "-DdurationSeconds=$DurationSeconds",
        "-DinjectionMode=$InjectionMode",
        "-DstartAtEpochMillis=$StartAtEpochMillis",
        "-DusersPerSecond=$RpsPerNode",
        "-DtargetUsersPerSecond=$RpsPerNode",
        "-DstatusPolls=$StatusPolls",
        "-DstatusPollPauseSeconds=$StatusPollPauseSeconds",
        "-DstatusPollPauseJitterSeconds=$StatusPollPauseJitterSeconds"
    )

    if (-not [string]::IsNullOrWhiteSpace($RunDescription)) {
        $args += @("--run-description", $RunDescription)
    }

    if ($EnableHttp2) {
        $args += "-Dhttp2Enabled=true"
    }

    if (-not [string]::IsNullOrWhiteSpace($AccessTokenMode)) {
        $args += "-DaccessTokenMode=$AccessTokenMode"
        if ($AccessTokenMode -eq "synthetic-jwt") {
            $args += "-DjwtSecret=$JwtSecret"
            $args += "-DjwtIssuer=$JwtIssuer"
            $args += "-DsyntheticMemberStartId=$SyntheticMemberStartId"
            $args += "-DsyntheticJwtRole=$SyntheticJwtRole"
            $args += "-DsyntheticTokenTtlSeconds=$SyntheticTokenTtlSeconds"
        } elseif ($AccessTokenMode -eq "tokens") {
            $tokenFile = if ([string]::IsNullOrWhiteSpace($NodeAccessTokensFile)) { $AccessTokensFile } else { $NodeAccessTokensFile }
            if (-not [string]::IsNullOrWhiteSpace($tokenFile)) {
                $args += "-DaccessTokensFile=$tokenFile"
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ReportDir)) {
        $args += "-DgatlingReportDir=$ReportDir"
    }

    if ($DumpFailureBody) {
        $args += "-DdumpFailureBody=true"
        $args += "-DdumpFailureBodyLimit=$DumpFailureBodyLimit"
        if (-not [string]::IsNullOrWhiteSpace($FailureBodyDir)) {
            $args += "-DfailureBodyDir=$FailureBodyDir"
        }
    }

    return $args
}

function New-AccessTokenGenerationArgs {
    param(
        [string]$Output,
        [long]$NodeMemberStartId
    )

    return @(
        "-p", "load-tests/gatling",
        "generateAccessTokens",
        "-DconsoleRunId=$ConsoleRunId",
        "-Doutput=$Output",
        "-DtokenCount=$EffectiveTokenCountPerNode",
        "-DjwtSecret=$JwtSecret",
        "-DjwtIssuer=$JwtIssuer",
        "-DsyntheticMemberStartId=$NodeMemberStartId",
        "-DsyntheticJwtRole=$SyntheticJwtRole",
        "-DsyntheticTokenTtlSeconds=$SyntheticTokenTtlSeconds"
    )
}

function New-RemoteCommand {
    param(
        [string]$NodeName,
        [string]$CollectReportDir,
        [long]$NodeMemberStartId,
        [string]$NodeAccessTokensFile,
        [string]$FailureBodyDir,
        [string]$CollectFailureBodyDir
    )

    $prepareTokens = ""
    if ($GenerateAccessTokens) {
        $tokenArgs = (New-AccessTokenGenerationArgs -Output $NodeAccessTokensFile -NodeMemberStartId $NodeMemberStartId) -join " "
        $prepareTokens = @"
./gradlew --console=plain $tokenArgs
token_status=`$?
if [ `$token_status -ne 0 ]; then exit `$token_status; fi
"@
    }

    $gatlingReportDir = $CollectReportDir -replace "^load-tests/gatling/", ""
    $gradleArgs = (New-GatlingArgs -ReportDir $gatlingReportDir -NodeAccessTokensFile $NodeAccessTokensFile -FailureBodyDir $FailureBodyDir) -join " "
    $command = @"
cd $RemoteProjectDir
chmod +x gradlew
$prepareTokens
collect_dir="$CollectReportDir"
failure_body_dir="$CollectFailureBodyDir"
rm -rf "`$collect_dir"
mkdir -p "`$collect_dir"
./gradlew --console=plain $gradleArgs
status=`$?
if [ -n "`$failure_body_dir" ] && [ -d "`$failure_body_dir" ]; then cp -R "`$failure_body_dir" "`$collect_dir/failure-bodies"; fi
exit `$status
"@
    return ConvertTo-RemoteBashCommand -Value $command
}

function Format-SummaryNumber {
    param([Nullable[double]]$Value)

    if ($null -eq $Value) {
        return ""
    }
    if ([Math]::Abs($Value - [Math]::Round($Value)) -lt 0.001) {
        return [string][int][Math]::Round($Value)
    }
    return $Value.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Measure-SummaryRows {
    param([object[]]$Rows)

    $metricRows = @($Rows | Where-Object { $null -ne $_.TotalRequests })
    if ($metricRows.Count -eq 0) {
        return $null
    }

    $totalRequests = ($metricRows | Measure-Object -Property TotalRequests -Sum).Sum
    $okRequests = ($metricRows | Measure-Object -Property OkRequests -Sum).Sum
    $koRequests = ($metricRows | Measure-Object -Property KoRequests -Sum).Sum
    $requestsPerSec = ($metricRows | Measure-Object -Property RequestsPerSec -Sum).Sum
    $minMs = ($metricRows | Where-Object { $null -ne $_.MinMs } | Measure-Object -Property MinMs -Minimum).Minimum
    $maxMs = ($metricRows | Where-Object { $null -ne $_.MaxMs } | Measure-Object -Property MaxMs -Maximum).Maximum
    $meanNumerator = ($metricRows |
        Where-Object { $null -ne $_.MeanMs -and $null -ne $_.TotalRequests } |
        ForEach-Object { $_.MeanMs * $_.TotalRequests } |
        Measure-Object -Sum).Sum

    return [pscustomobject]@{
        Nodes = $metricRows.Count
        TotalRequests = $totalRequests
        OkRequests = $okRequests
        KoRequests = $koRequests
        KoPercent = if ($totalRequests -gt 0) { ($koRequests * 100.0) / $totalRequests } else { $null }
        RequestsPerSec = $requestsPerSec
        MinMs = $minMs
        MeanMs = if ($totalRequests -gt 0) { $meanNumerator / $totalRequests } else { $null }
        MaxMs = $maxMs
    }
}

function Write-RunSummary {
    param([string]$RunDir)

    $rows = @()
    foreach ($log in Get-ChildItem -Path $RunDir -Filter "*.log" -File | Sort-Object Name) {
        $node = [System.IO.Path]::GetFileNameWithoutExtension($log.Name)
        $content = Get-Content -Path $log.FullName -Raw -ErrorAction SilentlyContinue
        $status = if ($content -match "BUILD FAILED|ERROR|Exception") { "FAILED" } elseif ($content -match "BUILD SUCCESSFUL") { "SUCCESS" } else { "UNKNOWN" }
        $report = Get-ChildItem -Path (Join-Path $RunDir $node) -Recurse -Filter index.html -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        $stats = if ($report) { Read-GatlingRootStats -ReportPath $report.FullName } else { $null }

        $rows += [pscustomobject]@{
            Node = $node
            Status = $status
            TotalRequests = if ($stats) { $stats.TotalRequests } else { $null }
            OkRequests = if ($stats) { $stats.OkRequests } else { $null }
            KoRequests = if ($stats) { $stats.KoRequests } else { $null }
            KoPercent = if ($stats) { $stats.KoPercent } else { $null }
            RequestsPerSec = if ($stats) { $stats.RequestsPerSec } else { $null }
            MinMs = if ($stats) { $stats.MinMs } else { $null }
            P50Ms = if ($stats) { $stats.P50Ms } else { $null }
            P75Ms = if ($stats) { $stats.P75Ms } else { $null }
            P95Ms = if ($stats) { $stats.P95Ms } else { $null }
            P99Ms = if ($stats) { $stats.P99Ms } else { $null }
            MaxMs = if ($stats) { $stats.MaxMs } else { $null }
            MeanMs = if ($stats) { $stats.MeanMs } else { $null }
            StdDevMs = if ($stats) { $stats.StdDevMs } else { $null }
            ReportPath = if ($report) { $report.FullName } else { "" }
            LogPath = $log.FullName
        }
    }

    $csvPath = Join-Path $RunDir "summary.csv"
    $mdPath = Join-Path $RunDir "summary.md"
    $rows | ConvertTo-Csv -NoTypeInformation | Set-Content -Path $csvPath -Encoding UTF8

    $overall = Measure-SummaryRows -Rows $rows
    $md = @("# Distributed Gatling Summary", "", "- Run directory: $RunDir", "")
    if ($overall) {
        $md += @(
            "## Overall (derived)",
            "",
            "| Nodes | Total | OK | KO | KO % | Cnt/s | Min ms | Mean ms | Max ms |",
            "|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
            "| $($overall.Nodes) | $(Format-SummaryNumber $overall.TotalRequests) | $(Format-SummaryNumber $overall.OkRequests) | $(Format-SummaryNumber $overall.KoRequests) | $(Format-SummaryNumber $overall.KoPercent) | $(Format-SummaryNumber $overall.RequestsPerSec) | $(Format-SummaryNumber $overall.MinMs) | $(Format-SummaryNumber $overall.MeanMs) | $(Format-SummaryNumber $overall.MaxMs) |",
            "",
            "> p50/p75/p95/p99 are shown per node. Exact global percentiles require raw response-time distribution across all nodes.",
            ""
        )
    }

    $md += @(
        "## Node Metrics",
        "",
        "| Node | Status | Total | OK | KO | KO % | Cnt/s | Min ms | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms | Report |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
    )
    foreach ($row in $rows) {
        $md += "| $($row.Node) | $($row.Status) | $(Format-SummaryNumber $row.TotalRequests) | $(Format-SummaryNumber $row.OkRequests) | $(Format-SummaryNumber $row.KoRequests) | $(Format-SummaryNumber $row.KoPercent) | $(Format-SummaryNumber $row.RequestsPerSec) | $(Format-SummaryNumber $row.MinMs) | $(Format-SummaryNumber $row.P50Ms) | $(Format-SummaryNumber $row.P75Ms) | $(Format-SummaryNumber $row.P95Ms) | $(Format-SummaryNumber $row.P99Ms) | $(Format-SummaryNumber $row.MaxMs) | $(Format-SummaryNumber $row.MeanMs) | $($row.ReportPath) |"
    }
    $md | Set-Content -Path $mdPath -Encoding UTF8

    Write-Host "Summary written:"
    Write-Host "  $csvPath"
    Write-Host "  $mdPath"
}

$Hosts = Normalize-Hosts -Values $Hosts
if ($Hosts.Count -eq 0) {
    throw "At least one SSH host is required"
}
if ($GenerateAccessTokens -and [string]::IsNullOrWhiteSpace($JwtSecret)) {
    throw "JwtSecret is required when GenerateAccessTokens is enabled"
}
if ($InjectionMode -notin @("constant-users-per-sec", "ticket-open")) {
    throw "Distributed Queue/CDN runner supports constant-users-per-sec or ticket-open: $InjectionMode"
}
if ($InjectionMode -eq "ticket-open" -and $Simulation -notlike "*QueueJoinOnlySimulation") {
    throw "ticket-open injection is supported only for QueueJoinOnlySimulation"
}
$requiredTokenCountPerNode = Get-ExpectedUsersPerNode
$EffectiveTokenCountPerNode = if ($TokenCountPerNode -gt 0) {
    $TokenCountPerNode
} else {
    $requiredTokenCountPerNode
}
if ($GenerateAccessTokens -and $EffectiveTokenCountPerNode -lt $requiredTokenCountPerNode) {
    throw "TokenCountPerNode is too small: required=$requiredTokenCountPerNode, configured=$EffectiveTokenCountPerNode"
}

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectoryName = New-RunDirectoryName
$runDir = New-UniqueRunDirectoryPath -Root $ReportRoot -Name $runDirectoryName
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Initialize-SshSession -RunDir $runDir

Write-Host "Starting distributed Gatling run"
Write-Host "Remote nodes: $($Hosts.Count)"
Write-Host "Include local: $IncludeLocal"
Write-Host "RPS per node: $RpsPerNode"
Write-Host "Expected total RPS: $($RpsPerNode * ($Hosts.Count + [int]$IncludeLocal.IsPresent))"
Write-Host "Duration seconds: $DurationSeconds"
Write-Host "HTTP/2 enabled: $($EnableHttp2.IsPresent)"
if (-not [string]::IsNullOrWhiteSpace($AccessTokenMode)) {
    Write-Host "Access token mode: $AccessTokenMode"
}
Write-Host "Injection mode: $InjectionMode"
if ($GenerateAccessTokens) {
    Write-Host "Generated tokens per node: $EffectiveTokenCountPerNode"
}
Write-Host "Sync project: $SyncProject"
Write-Host "Skip preflight: $SkipPreflight"
Write-Host "Run dir: $runDir"

$projectArchive = ""
if ($SyncProject) {
    Write-Host "Creating local project archive"
    $projectArchive = New-ProjectArchive -RunDir $runDir
    foreach ($hostName in $Hosts) {
        Write-Host "Syncing project to $hostName"
        Sync-RemoteProject -HostName $hostName -ArchivePath $projectArchive -StartedAt $startedAt
    }
}

if (-not $SkipPreflight) {
    foreach ($hostName in $Hosts) {
        Write-Host "Preflight remote project: $hostName"
        Test-RemoteProject -HostName $hostName
    }
}

$jobs = @()
$remoteReportRoots = @{}
$nodeIndex = 0

if ($InjectionMode -eq "ticket-open" -and $StartAtEpochMillis -le 0) {
    $StartAtEpochMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 120000
}
if ($StartAtEpochMillis -gt 0) {
    Write-Host "Scheduled start UTC: $([DateTimeOffset]::FromUnixTimeMilliseconds($StartAtEpochMillis).UtcDateTime.ToString('O'))"
}

foreach ($hostName in $Hosts) {
    $safeName = New-SafeNodeName $hostName
    $logPath = Join-Path $runDir "$safeName.log"
    $remoteReportRoot = "load-tests/gatling/build/reports/distributed/$startedAt/$safeName"
    $remoteTokenFile = "load-tests/gatling/build/tmp/distributed-access-tokens/$startedAt/$safeName/access-tokens.txt"
    $remoteGatlingTokenFile = if ($GenerateAccessTokens) { $remoteTokenFile } else { "" }
    $remoteFailureBodyDir = "build/reports/failure-bodies/$startedAt/$safeName"
    $remoteCollectFailureBodyDir = "load-tests/gatling/$remoteFailureBodyDir"
    $nodeMemberStartId = $SyntheticMemberStartId + ([long]$nodeIndex * [long]$EffectiveTokenCountPerNode)
    $remoteReportRoots[$safeName] = $remoteReportRoot
    $remoteCommand = New-RemoteCommand `
        -NodeName $safeName `
        -CollectReportDir $remoteReportRoot `
        -NodeMemberStartId $nodeMemberStartId `
        -NodeAccessTokensFile $remoteGatlingTokenFile `
        -FailureBodyDir $remoteFailureBodyDir `
        -CollectFailureBodyDir $remoteCollectFailureBodyDir

    $jobs += Start-RemoteNodeJob -Name $safeName -HostName $hostName -Command $remoteCommand -LogPath $logPath

    $nodeIndex++
}

if ($IncludeLocal) {
    $localLogPath = Join-Path $runDir "local.log"
    $localReportRoot = "load-tests\gatling\build\reports\distributed\$startedAt\local"
    $localTokenFile = "load-tests\gatling\build\tmp\distributed-access-tokens\$startedAt\local\access-tokens.txt"
    $localGatlingTokenFile = if ($GenerateAccessTokens) { $localTokenFile } else { "" }
    $localFailureBodyDir = "build\reports\failure-bodies\$startedAt\local"
    $localCollectFailureBodyDir = Join-Path $LocalProjectDir "load-tests\gatling\$localFailureBodyDir"
    $localMemberStartId = $SyntheticMemberStartId + ([long]$Hosts.Count * [long]$EffectiveTokenCountPerNode)
    $localTokenArgs = if ($GenerateAccessTokens) {
        New-AccessTokenGenerationArgs -Output $localTokenFile -NodeMemberStartId $localMemberStartId
    } else {
        @()
    }
    $localArgs = New-GatlingArgs -ReportDir $localReportRoot -NodeAccessTokensFile $localGatlingTokenFile -FailureBodyDir $localFailureBodyDir

    $jobs += Start-Job -Name "local" -ScriptBlock {
        param($ProjectDir, $TokenArguments, $Arguments, $LogPath)
        Set-Location $ProjectDir
        if ($TokenArguments.Count -gt 0) {
            & ".\gradlew.bat" --console=plain @TokenArguments *> $LogPath
            if ($LASTEXITCODE -ne 0) {
                return $LASTEXITCODE
            }
        }
        & ".\gradlew.bat" --console=plain @Arguments *>> $LogPath
        return $LASTEXITCODE
    } -ArgumentList $LocalProjectDir, $localTokenArgs, $localArgs, $localLogPath
}

Write-Host "Started jobs:"
$jobs | Select-Object Id, Name, State | Format-Table

$failedJobs = @(Wait-NodeJobs -Jobs $jobs | Where-Object { $_.ExitCode -ne 0 })

if ($failedJobs.Count -gt 0) {
    Write-Warning "Some Gatling jobs failed:"
    $failedJobs | Select-Object Node | Format-Table
    Write-Warning "Check logs under $runDir"
} else {
    Write-Host "All Gatling jobs completed successfully"
}

if ($CollectReports) {
    Write-Host "Collecting remote Gatling reports"
    foreach ($hostName in $Hosts) {
        $safeName = New-SafeNodeName $hostName
        $targetDir = Join-Path (Join-Path $runDir $safeName) "gatling"
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        $remoteReportRoot = $remoteReportRoots[$safeName]
        $source = "${hostName}:$RemoteProjectDir/$remoteReportRoot/*"
        & $ScpCommand @ScpOptions -r $source $targetDir
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not collect Gatling report from ${hostName}: $RemoteProjectDir/$remoteReportRoot"
        }
    }

    if ($IncludeLocal) {
        $localReports = Join-Path $LocalProjectDir "load-tests\gatling\build\reports\distributed\$startedAt\local"
        $localTarget = Join-Path (Join-Path $runDir "local") "gatling"
        New-Item -ItemType Directory -Force -Path $localTarget | Out-Null
        if (Test-Path $localReports) {
            Copy-Item -Recurse -Force $localReports $localTarget
        }
        if ($DumpFailureBody -and (Test-Path $localCollectFailureBodyDir)) {
            New-Item -ItemType Directory -Force -Path $localTarget | Out-Null
            Copy-Item -Recurse -Force $localCollectFailureBodyDir (Join-Path $localTarget "failure-bodies")
        }
    }
}

Write-RunSummary -RunDir $runDir

if ($failedJobs.Count -gt 0) {
    exit 1
}

Write-Host "Done"
Write-Host "Logs and collected reports: $runDir"
