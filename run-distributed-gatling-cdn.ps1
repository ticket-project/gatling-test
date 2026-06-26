param(
    [string]$KeyPath = "C:\Users\mn040\Desktop\ticket\ticket-test-key-01.pem",
    [string[]]$Hosts = @(
        "ubuntu@43.203.155.15",
        "ubuntu@15.165.40.25",
        "ubuntu@43.203.136.184"
    ),
    [string]$RemoteProjectDir = "~/gatling-test",
    [string]$BaseUrl = "https://queue.oneticket.site",
    [string]$Simulation = "com.ticket.loadtest.simulation.CdnPublicStateSimulation",
    [int]$PerformanceId = 1,
    [int]$RpsPerNode = 100,
    [int]$DurationSeconds = 300,
    [int]$StatusPolls = 1,
    [int]$StatusPollPauseSeconds = 0,
    [int]$StatusPollPauseJitterSeconds = 0,
    [string]$AccessTokenMode = "",
    [string]$AccessTokensFile = "",
    [switch]$GenerateAccessTokens,
    [int]$TokenCountPerNode = 0,
    [string]$JwtSecret = "",
    [string]$JwtIssuer = "ticket",
    [long]$SyntheticMemberStartId = 1,
    [string]$SyntheticJwtRole = "MEMBER",
    [int]$SyntheticTokenTtlSeconds = 3600,
    [switch]$SyncProject,
    [switch]$SkipPreflight,
    [switch]$IncludeLocal,
    [switch]$CollectReports,
    [string]$LocalProjectDir = (Join-Path $PSScriptRoot "."),
    [string]$ReportRoot = (Join-Path $PSScriptRoot "distributed-results")
)

$ErrorActionPreference = "Stop"

function Resolve-CommandPath {
    param(
        [string]$Name,
        [string[]]$Candidates = @()
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    foreach ($candidate in $Candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "Required command not found: $Name"
}

function New-SafeNodeName {
    param([string]$Value)
    return $Value.Replace("@", "_").Replace(".", "_").Replace(":", "_")
}

function Normalize-Hosts {
    param([string[]]$Values)

    return @($Values |
        ForEach-Object { $_ -split "[,\r\n]+" } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function New-SshOptions {
    param([string]$KnownHostsFile)

    $options = @(
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=5",
        "-o", "ServerAliveCountMax=2",
        "-o", "StrictHostKeyChecking=accept-new"
    )
    if (-not [string]::IsNullOrWhiteSpace($KnownHostsFile)) {
        $options += @("-o", "UserKnownHostsFile=$KnownHostsFile")
    }
    $options += @("-i", $KeyPath)
    return $options
}

function New-ScpOptions {
    param([string]$KnownHostsFile)

    $options = @(
        "-B",
        "-o", "ConnectTimeout=15",
        "-o", "StrictHostKeyChecking=accept-new"
    )
    if (-not [string]::IsNullOrWhiteSpace($KnownHostsFile)) {
        $options += @("-o", "UserKnownHostsFile=$KnownHostsFile")
    }
    $options += @("-i", $KeyPath)
    return $options
}

function New-GatlingArgs {
    param(
        [string]$ReportDir = "",
        [string]$NodeAccessTokensFile = ""
    )

    $args = @(
        "-p", "load-tests/gatling",
        "gatlingRun",
        "--simulation", $Simulation,
        "-DbaseUrl=$BaseUrl",
        "-DperformanceId=$PerformanceId",
        "-Dusers=1",
        "-DdurationSeconds=$DurationSeconds",
        "-DinjectionMode=constant-users-per-sec",
        "-DusersPerSecond=$RpsPerNode",
        "-DtargetUsersPerSecond=$RpsPerNode",
        "-DstatusPolls=$StatusPolls",
        "-DstatusPollPauseSeconds=$StatusPollPauseSeconds",
        "-DstatusPollPauseJitterSeconds=$StatusPollPauseJitterSeconds"
    )

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
        "-Doutput=$Output",
        "-DtokenCount=$EffectiveTokenCountPerNode",
        "-DjwtSecret=$JwtSecret",
        "-DjwtIssuer=$JwtIssuer",
        "-DsyntheticMemberStartId=$NodeMemberStartId",
        "-DsyntheticJwtRole=$SyntheticJwtRole",
        "-DsyntheticTokenTtlSeconds=$SyntheticTokenTtlSeconds"
    )
}

function New-ProjectArchive {
    param([string]$RunDir)

    $archivePath = Join-Path $RunDir "gatling-test-project.tgz"
    $tarArgs = @(
        "-czf", $archivePath,
        "--exclude=.git",
        "--exclude=.gradle",
        "--exclude=.tmp",
        "--exclude=distributed-results",
        "--exclude=distributed-results-join",
        "--exclude=distributed-results-legacy",
        "--exclude=console/build",
        "--exclude=load-tests/gatling/build",
        "-C", $LocalProjectDir,
        "."
    )
    & $TarCommand @tarArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Project archive creation failed with exit code $LASTEXITCODE"
    }
    return $archivePath
}

function Sync-RemoteProject {
    param(
        [string]$HostName,
        [string]$ArchivePath,
        [string]$StartedAt
    )

    $remoteArchive = "/tmp/gatling-test-$StartedAt.tgz"
    $target = "${HostName}:$remoteArchive"
    & $ScpCommand @ScpOptions $ArchivePath $target
    if ($LASTEXITCODE -ne 0) {
        throw "Project sync upload failed for ${HostName}"
    }

    $syncCommand = "timeout 120s bash -lc 'set -e; mkdir -p $RemoteProjectDir; tar -xzf $remoteArchive -C $RemoteProjectDir; rm -f $remoteArchive; chmod +x $RemoteProjectDir/gradlew; test -d $RemoteProjectDir/load-tests/gatling; echo project-sync-ok'"
    & $SshCommand @SshOptions $HostName $syncCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Project sync extraction failed for ${HostName}"
    }
}

function Test-RemoteProject {
    param([string]$HostName)

    $command = "timeout 30s bash -lc 'set -e; echo preflight-host=`$(hostname); test -d $RemoteProjectDir; test -f $RemoteProjectDir/gradlew; test -d $RemoteProjectDir/load-tests/gatling; test -f $RemoteProjectDir/load-tests/gatling/build.gradle; command -v tar >/dev/null; command -v java >/dev/null; echo remote-preflight-ok'"
    & $SshCommand @SshOptions $HostName $command
    if ($LASTEXITCODE -ne 0) {
        throw "Remote Gatling project preflight failed for ${HostName}: $RemoteProjectDir"
    }
}

function New-RemoteCommand {
    param(
        [string]$NodeName,
        [string]$CollectReportDir,
        [long]$NodeMemberStartId,
        [string]$NodeAccessTokensFile
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

    $gradleArgs = (New-GatlingArgs -NodeAccessTokensFile $NodeAccessTokensFile) -join " "
    return @"
cd $RemoteProjectDir
chmod +x gradlew
$prepareTokens
./gradlew --console=plain $gradleArgs
status=`$?
latest=`$(ls -td load-tests/gatling/build/reports/gatling/*/ 2>/dev/null | head -1)
collect_dir="$CollectReportDir"
rm -rf "`$collect_dir"
mkdir -p "`$collect_dir"
if [ -n "`$latest" ]; then cp -R "`$latest" "`$collect_dir/"; fi
exit `$status
"@
}

function Write-RunSummary {
    param([string]$RunDir)

    $rows = @()
    foreach ($log in Get-ChildItem -Path $RunDir -Filter "*.log" -File | Sort-Object Name) {
        $node = [System.IO.Path]::GetFileNameWithoutExtension($log.Name)
        $content = Get-Content -Path $log.FullName -Raw -ErrorAction SilentlyContinue
        $status = if ($content -match "BUILD SUCCESSFUL") { "SUCCESS" } elseif ($content -match "BUILD FAILED|ERROR|Exception") { "FAILED" } else { "UNKNOWN" }
        $report = Get-ChildItem -Path (Join-Path $RunDir $node) -Recurse -Filter index.html -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

        $rows += [pscustomobject]@{
            Node = $node
            Status = $status
            ReportPath = if ($report) { $report.FullName } else { "" }
            LogPath = $log.FullName
        }
    }

    $csvPath = Join-Path $RunDir "summary.csv"
    $mdPath = Join-Path $RunDir "summary.md"
    $rows | ConvertTo-Csv -NoTypeInformation | Set-Content -Path $csvPath -Encoding UTF8

    $md = @("# Distributed Gatling Summary", "", "- Run directory: $RunDir", "", "| Node | Status | Report |", "|---|---:|---|")
    foreach ($row in $rows) {
        $md += "| $($row.Node) | $($row.Status) | $($row.ReportPath) |"
    }
    $md | Set-Content -Path $mdPath -Encoding UTF8

    Write-Host "Summary written:"
    Write-Host "  $csvPath"
    Write-Host "  $mdPath"
}

$windowsRoot = if ([string]::IsNullOrWhiteSpace($env:SystemRoot)) { "C:\Windows" } else { $env:SystemRoot }
$sshCandidates = @(
    (Join-Path $windowsRoot "System32\OpenSSH\ssh.exe"),
    (Join-Path $windowsRoot "Sysnative\OpenSSH\ssh.exe"),
    "C:\Windows\System32\OpenSSH\ssh.exe",
    "C:\Windows\Sysnative\OpenSSH\ssh.exe"
)
$scpCandidates = @(
    (Join-Path $windowsRoot "System32\OpenSSH\scp.exe"),
    (Join-Path $windowsRoot "Sysnative\OpenSSH\scp.exe"),
    "C:\Windows\System32\OpenSSH\scp.exe",
    "C:\Windows\Sysnative\OpenSSH\scp.exe"
)

$SshCommand = Resolve-CommandPath -Name "ssh" -Candidates $sshCandidates
$ScpCommand = ""
if ($CollectReports -or $SyncProject) {
    $ScpCommand = Resolve-CommandPath -Name "scp" -Candidates $scpCandidates
}
if ($SyncProject) {
    $TarCommand = Resolve-CommandPath -Name "tar" -Candidates @(
        (Join-Path $windowsRoot "System32\tar.exe"),
        (Join-Path $windowsRoot "Sysnative\tar.exe"),
        "C:\Windows\System32\tar.exe",
        "C:\Windows\Sysnative\tar.exe"
    )
}
if (-not (Test-Path $KeyPath)) {
    throw "SSH key not found: $KeyPath"
}
$Hosts = Normalize-Hosts -Values $Hosts
if ($Hosts.Count -eq 0) {
    throw "At least one SSH host is required"
}
if ($GenerateAccessTokens -and [string]::IsNullOrWhiteSpace($JwtSecret)) {
    throw "JwtSecret is required when GenerateAccessTokens is enabled"
}

$EffectiveTokenCountPerNode = if ($TokenCountPerNode -gt 0) {
    $TokenCountPerNode
} else {
    [Math]::Max(1, [int][Math]::Ceiling($RpsPerNode * $DurationSeconds))
}

$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $ReportRoot $startedAt
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$knownHostsFile = Join-Path $runDir "known_hosts"
New-Item -ItemType File -Force -Path $knownHostsFile | Out-Null
$SshOptions = New-SshOptions -KnownHostsFile $knownHostsFile
$ScpOptions = New-ScpOptions -KnownHostsFile $knownHostsFile

Write-Host "Starting distributed Gatling run"
Write-Host "Remote nodes: $($Hosts.Count)"
Write-Host "Include local: $IncludeLocal"
Write-Host "RPS per node: $RpsPerNode"
Write-Host "Expected total RPS: $($RpsPerNode * ($Hosts.Count + [int]$IncludeLocal.IsPresent))"
Write-Host "Duration seconds: $DurationSeconds"
if (-not [string]::IsNullOrWhiteSpace($AccessTokenMode)) {
    Write-Host "Access token mode: $AccessTokenMode"
}
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

foreach ($hostName in $Hosts) {
    $safeName = New-SafeNodeName $hostName
    $logPath = Join-Path $runDir "$safeName.log"
    $remoteReportRoot = "load-tests/gatling/build/reports/distributed/$startedAt/$safeName"
    $remoteTokenFile = "load-tests/gatling/build/tmp/distributed-access-tokens/$startedAt/$safeName/access-tokens.txt"
    $remoteGatlingTokenFile = if ($GenerateAccessTokens) { $remoteTokenFile } else { "" }
    $nodeMemberStartId = $SyntheticMemberStartId + ([long]$nodeIndex * [long]$EffectiveTokenCountPerNode)
    $remoteReportRoots[$safeName] = $remoteReportRoot
    $remoteCommand = New-RemoteCommand `
        -NodeName $safeName `
        -CollectReportDir $remoteReportRoot `
        -NodeMemberStartId $nodeMemberStartId `
        -NodeAccessTokensFile $remoteGatlingTokenFile

    $jobs += Start-Job -Name $safeName -ScriptBlock {
        param($SshCommand, $SshOptions, $HostName, $Command, $LogPath)
        & $SshCommand @SshOptions $HostName $Command *> $LogPath
        return $LASTEXITCODE
    } -ArgumentList $SshCommand, $SshOptions, $hostName, $remoteCommand, $logPath

    $nodeIndex++
}

if ($IncludeLocal) {
    $localLogPath = Join-Path $runDir "local.log"
    $localReportRoot = "load-tests\gatling\build\reports\distributed\$startedAt\local"
    $localTokenFile = "load-tests\gatling\build\tmp\distributed-access-tokens\$startedAt\local\access-tokens.txt"
    $localGatlingTokenFile = if ($GenerateAccessTokens) { $localTokenFile } else { "" }
    $localMemberStartId = $SyntheticMemberStartId + ([long]$Hosts.Count * [long]$EffectiveTokenCountPerNode)
    $localTokenArgs = if ($GenerateAccessTokens) {
        New-AccessTokenGenerationArgs -Output $localTokenFile -NodeMemberStartId $localMemberStartId
    } else {
        @()
    }
    $localArgs = New-GatlingArgs -ReportDir $localReportRoot -NodeAccessTokensFile $localGatlingTokenFile

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

Wait-Job $jobs | Out-Null

$failedJobs = @()
foreach ($job in $jobs) {
    $exitCode = Receive-Job $job
    if ($exitCode -ne 0) {
        $failedJobs += $job
    }
}
Remove-Job $jobs

if ($failedJobs.Count -gt 0) {
    Write-Warning "Some Gatling jobs failed:"
    $failedJobs | Select-Object Name | Format-Table
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
        $source = "${hostName}:$RemoteProjectDir/$remoteReportRoot"
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
    }
}

Write-RunSummary -RunDir $runDir

Write-Host "Done"
Write-Host "Logs and collected reports: $runDir"
