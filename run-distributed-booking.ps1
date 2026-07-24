param(
    [string]$KeyPath = "",
    [string[]]$Hosts = @(
        "ubuntu@43.203.155.15",
        "ubuntu@15.165.40.25",
        "ubuntu@43.203.136.184"
    ),
    [string]$RemoteProjectDir = "~/gatling-test",
    [string]$ConsoleRunId = "",
    [string]$Simulation = "com.ticket.loadtest.simulation.BookingCapacitySimulation",
    [string]$CoreBaseUrl = "",
    [string]$QueueBaseUrl = "",
    [int]$PerformanceId = 1,
    [string]$FeederFile = "build/booking-feeder.csv",
    [int]$RpsPerNode = 10,
    [int]$DurationSeconds = 300,
    [string]$InjectionMode = "constant-users-per-sec",
    [int]$PollingTimeoutSeconds = 300,
    [int]$StatusPollPauseSeconds = 1,
    [int]$StatusPollPauseJitterSeconds = 0,
    [switch]$SkipSyncProject,
    [switch]$SkipPreflight,
    [switch]$CollectReports,
    [switch]$CleanupRemote,
    [string]$LocalProjectDir = (Join-Path $PSScriptRoot "."),
    [string]$ReportRoot = (Join-Path $PSScriptRoot "distributed-results-booking"),
    [double]$TechnicalFailureThresholdPercent = 1.0,
    [int]$QueueP99ThresholdMs = 2000,
    [int]$TicketP99ThresholdMs = 3000
)

$ErrorActionPreference = "Stop"

function Stop-Validation {
    param([string]$Message)
    Write-Error $Message
    exit 2
}

function Resolve-CommandPath {
    param([string]$Name, [string[]]$Candidates = @())
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }
    throw "Required command not found: $Name"
}

function Normalize-Hosts {
    param([string[]]$Values)
    return @($Values |
        ForEach-Object { $_ -split "[,`r`n]+" } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Assert-RemoteUrl {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { Stop-Validation "$Name is required" }
    $uri = $null
    if (-not [Uri]::TryCreate($Value.Trim(), [UriKind]::Absolute, [ref]$uri)) {
        Stop-Validation "$Name must be an absolute URL: $Value"
    }
    if ($uri.Scheme -notin @("http", "https")) { Stop-Validation "$Name must use http or https: $Value" }
    if ($uri.Host -in @("localhost", "127.0.0.1", "::1")) { Stop-Validation "$Name must not point to localhost for distributed execution: $Value" }
}

function New-SafeNodeName {
    param([string]$Value)
    return $Value.Replace("@", "_").Replace(".", "_").Replace(":", "_")
}

function Get-BookingScenario {
    if ($Simulation -like "*BookingCapacitySimulation") { return "BOOKING_CAPACITY" }
    if ($Simulation -like "*TicketOpenEndToEndSimulation") { return "TICKET_OPEN_END_TO_END" }
    if ($Simulation -like "*SeatContentionSimulation") { return "SEAT_CONTENTION" }
    Stop-Validation "Unsupported booking simulation: $Simulation"
}

function Get-SimulationRunName {
    return (Get-BookingScenario).ToLowerInvariant().Replace("_", "-")
}

function Import-BookingFeederRows {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Validation "FeederFile not found: $Path" }
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).ProviderPath)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Stop-Validation "FeederFile must be UTF-8 without BOM: $Path"
    }
    $lines = [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).ProviderPath, [Text.Encoding]::UTF8)
    if ($lines.Count -eq 0 -or $lines[0] -ne "memberId,accessToken,seatId,admissionToken") {
        Stop-Validation "FeederFile header must be exactly: memberId,accessToken,seatId,admissionToken"
    }
    $rows = New-Object System.Collections.Generic.List[string]
    $members = @{}
    for ($index = 1; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $columns = $line.Split(",")
        if ($columns.Count -ne 4) { Stop-Validation "Invalid feeder row $($index + 1): exactly 4 columns are required" }
        $memberId = 0L
        $seatId = 0L
        if (-not [long]::TryParse($columns[0].Trim(), [ref]$memberId) -or $memberId -le 0) { Stop-Validation "Invalid feeder row $($index + 1): memberId must be positive" }
        if (-not [long]::TryParse($columns[2].Trim(), [ref]$seatId) -or $seatId -le 0) { Stop-Validation "Invalid feeder row $($index + 1): seatId must be positive" }
        if ([string]::IsNullOrWhiteSpace($columns[1])) { Stop-Validation "Invalid feeder row $($index + 1): accessToken is required" }
        if ((Get-BookingScenario) -ne "TICKET_OPEN_END_TO_END" -and [string]::IsNullOrWhiteSpace($columns[3])) {
            Stop-Validation "Invalid feeder row $($index + 1): admissionToken is required"
        }
        if ($members.ContainsKey($memberId)) { Stop-Validation "Invalid feeder row $($index + 1): memberId must be unique" }
        $members[$memberId] = $true
        $rows.Add($line)
    }
    return $rows
}

function New-NodeFeeders {
    param([System.Collections.Generic.List[string]]$Rows, [int]$TotalNodes, [string]$RunDir)
    $rowsPerNode = [Math]::Ceiling($RpsPerNode * $DurationSeconds)
    $requiredRows = [int]($rowsPerNode * $TotalNodes)
    if ($Rows.Count -lt $requiredRows) {
        Stop-Validation "FeederFile has fewer rows than required: required=$requiredRows, actual=$($Rows.Count), rowsPerNode=$rowsPerNode, nodes=$TotalNodes"
    }

    $feederDir = Join-Path $RunDir "feeders"
    New-Item -ItemType Directory -Force -Path $feederDir | Out-Null
    $manifestRows = @()
    $utf8NoBom = New-Object Text.UTF8Encoding($false)

    for ($nodeIndex = 0; $nodeIndex -lt $TotalNodes; $nodeIndex++) {
        $start = [int]($nodeIndex * $rowsPerNode)
        $endExclusive = [int]($start + $rowsPerNode)
        $nodeRows = @("memberId,accessToken,seatId,admissionToken") + @($Rows[$start..($endExclusive - 1)])
        $nodeFile = Join-Path $feederDir "booking-feeder-node-$nodeIndex.csv"
        [IO.File]::WriteAllLines($nodeFile, $nodeRows, $utf8NoBom)
        $manifestRows += [pscustomobject]@{
            nodeIndex = $nodeIndex
            totalNodes = $TotalNodes
            globalRps = $RpsPerNode * $TotalNodes
            nodeRps = $RpsPerNode
            rowStart = $start + 1
            rowEnd = $endExclusive
            feederFile = $nodeFile
        }
    }

    $manifestPath = Join-Path $RunDir "manifest.csv"
    $manifestRows | Export-Csv -Path $manifestPath -NoTypeInformation -Encoding UTF8
    return $manifestRows
}

function Resolve-DefaultSshKeyPath {
    param([string]$Value)
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }
    $userRoot = if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) { [Environment]::GetFolderPath("UserProfile") } else { $env:USERPROFILE }
    return Join-Path $userRoot "Desktop\ticket\ticket-test-key-01.pem"
}

function New-OpenSshKeyPath {
    param([string]$SourcePath)
    $resolvedPath = (Resolve-Path -LiteralPath $SourcePath).ProviderPath
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { return $resolvedPath }
    $keyRoot = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Join-Path ([IO.Path]::GetTempPath()) "ticket-gatling\ssh-keys" } else { Join-Path $env:LOCALAPPDATA "ticket-gatling\ssh-keys" }
    New-Item -ItemType Directory -Force -Path $keyRoot | Out-Null
    $targetPath = Join-Path $keyRoot "booking-load-test-key.pem"
    Copy-Item -LiteralPath $resolvedPath -Destination $targetPath -Force
    $icacls = Resolve-CommandPath -Name "icacls.exe" -Candidates @("C:\Windows\System32\icacls.exe", "C:\Windows\Sysnative\icacls.exe")
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & $icacls $targetPath /inheritance:r | Out-Null
    & $icacls $targetPath /grant:r "${currentUser}:F" | Out-Null
    return $targetPath
}

function New-SshOptions {
    param([string]$KnownHostsFile)
    return @("-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2", "-o", "StrictHostKeyChecking=accept-new", "-o", "UserKnownHostsFile=$KnownHostsFile", "-i", $KeyPath)
}

function New-ScpOptions {
    param([string]$KnownHostsFile)
    return @("-B", "-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=accept-new", "-o", "UserKnownHostsFile=$KnownHostsFile", "-i", $KeyPath)
}

function New-RunDirectoryName {
    $name = "$(Get-SimulationRunName)_pid$PerformanceId`_rps$RpsPerNode`_nodes$($Hosts.Count)`_dur${DurationSeconds}s"
    return ($name -replace "[^A-Za-z0-9._-]", "-").Trim("._-")
}

function New-UniqueRunDirectoryPath {
    param([string]$Root, [string]$Name)
    $candidate = Join-Path $Root $Name
    for ($index = 1; Test-Path -LiteralPath $candidate; $index++) {
        $candidate = Join-Path $Root "$Name`_$index"
    }
    return $candidate
}

function New-ProjectArchive {
    param([string]$RunDir)
    $archivePath = Join-Path $RunDir "gatling-test-project.tgz"
    & $TarCommand -czf $archivePath --exclude=.git --exclude=.gradle --exclude=.tmp --exclude=distributed-results --exclude=distributed-results-booking --exclude=console/build --exclude=load-tests/gatling/build -C $LocalProjectDir .
    if ($LASTEXITCODE -ne 0) { throw "Project archive creation failed with exit code $LASTEXITCODE" }
    return $archivePath
}

function Sync-RemoteProject {
    param([string]$HostName, [string]$ArchivePath, [string]$StartedAt)
    $remoteArchive = "/tmp/gatling-booking-$StartedAt.tgz"
    & $ScpCommand @ScpOptions $ArchivePath "${HostName}:$remoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "Project sync upload failed for ${HostName}" }
    $command = "timeout 120s bash -lc 'set -e; mkdir -p $RemoteProjectDir; tar -xzf $remoteArchive -C $RemoteProjectDir; rm -f $remoteArchive; chmod +x $RemoteProjectDir/gradlew; test -d $RemoteProjectDir/load-tests/gatling; echo project-sync-ok'"
    & $SshCommand @SshOptions $HostName $command
    if ($LASTEXITCODE -ne 0) { throw "Project sync extraction failed for ${HostName}" }
}

function Test-RemoteProject {
    param([string]$HostName)
    $command = "timeout 30s bash -lc 'set -e; test -d $RemoteProjectDir; test -f $RemoteProjectDir/gradlew; test -d $RemoteProjectDir/load-tests/gatling; command -v java >/dev/null; command -v tar >/dev/null; echo remote-preflight-ok'"
    & $SshCommand @SshOptions $HostName $command
    if ($LASTEXITCODE -ne 0) { throw "Remote Gatling project preflight failed for ${HostName}: $RemoteProjectDir" }
}

function New-GatlingArgs {
    param([int]$NodeIndex, [string]$RemoteFeederFile, [string]$RemoteResultFile)
    return @(
        "-p", "load-tests/gatling",
        "gatlingRun",
        "--simulation", $Simulation,
        "-DconsoleRunId=$ConsoleRunId",
        "-DcoreBaseUrl=$CoreBaseUrl",
        "-DqueueBaseUrl=$QueueBaseUrl",
        "-DperformanceId=$PerformanceId",
        "-DbookingFeederFile=$RemoteFeederFile",
        "-DbookingScenario=$(Get-BookingScenario)",
        "-DnodeIndex=$NodeIndex",
        "-DresultFile=$RemoteResultFile",
        "-Dusers=$([Math]::Ceiling($RpsPerNode * $DurationSeconds))",
        "-DdurationSeconds=$DurationSeconds",
        "-DinjectionMode=$InjectionMode",
        "-DusersPerSecond=$RpsPerNode",
        "-DtargetUsersPerSecond=$RpsPerNode",
        "-DpollingTimeoutSeconds=$PollingTimeoutSeconds",
        "-DstatusPollPauseSeconds=$StatusPollPauseSeconds",
        "-DstatusPollPauseJitterSeconds=$StatusPollPauseJitterSeconds"
    )
}

function New-RemoteCommand {
    param([int]$NodeIndex, [string]$CollectDir, [string]$RemoteFeederFile, [string]$RemoteResultFile)
    $args = (New-GatlingArgs -NodeIndex $NodeIndex -RemoteFeederFile $RemoteFeederFile -RemoteResultFile $RemoteResultFile) -join " "
    $cleanup = if ($CleanupRemote) { "rm -f '$RemoteFeederFile'" } else { "true" }
    $command = @"
cd $RemoteProjectDir
chmod +x gradlew
./gradlew --console=plain $args
status=`$?
latest=`$(ls -td load-tests/gatling/build/reports/gatling/*/ 2>/dev/null | head -1)
rm -rf '$CollectDir'
mkdir -p '$CollectDir'
if [ -n "`$latest" ]; then cp -R "`$latest" '$CollectDir/gatling-report'; fi
if [ -f '$RemoteResultFile' ]; then cp '$RemoteResultFile' '$CollectDir/booking-results.csv'; fi
$cleanup
exit `$status
"@
    return (($command -replace "`r`n", "`n") -replace "`r", "`n")
}

function ConvertTo-SummaryNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $normalized = [Net.WebUtility]::HtmlDecode($Value).Trim().Replace(",", "")
    $number = 0.0
    if ([double]::TryParse($normalized, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) { return $number }
    return $null
}

function Read-GatlingRootStats {
    param([string]$ReportPath)
    if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) { return $null }
    $html = Get-Content -LiteralPath $ReportPath -Raw -Encoding UTF8
    $rowMatch = [regex]::Match($html, '<tr[^>]*id="ROOT"[^>]*>.*?</tr>', [Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $rowMatch.Success) { return $null }
    $values = @{}
    foreach ($match in [regex]::Matches($rowMatch.Value, '<td class="value [^"]* col-(\d+)">([^<]*)</td>')) {
        $values[[int]$match.Groups[1].Value] = ConvertTo-SummaryNumber -Value $match.Groups[2].Value
    }
    return [pscustomobject]@{ TotalRequests = $values[2]; KoRequests = $values[4]; KoPercent = $values[5]; P99Ms = $values[11] }
}

function Write-BookingSummary {
    param([string]$RunDir, [object[]]$JobRows)
    $resultFiles = @(Get-ChildItem -Path $RunDir -Recurse -Filter booking-results.csv -File -ErrorAction SilentlyContinue)
    $resultRows = @()
    foreach ($file in $resultFiles) { $resultRows += @(Import-Csv -Path $file.FullName) }

    $successRows = @($resultRows | Where-Object { $_.result -eq "SUCCESS" })
    $businessRows = @($resultRows | Where-Object { $_.result -like "BUSINESS_REJECTED_*" -or $_.result -like "SELECT_BUSINESS_REJECTED_*" })
    $queueTimeoutRows = @($resultRows | Where-Object { $_.result -eq "QUEUE_TIMEOUT" })
    $technicalRows = @($resultRows | Where-Object { $_.result -ne "SUCCESS" -and $_.result -notlike "BUSINESS_REJECTED_*" -and $_.result -notlike "SELECT_BUSINESS_REJECTED_*" -and $_.result -ne "QUEUE_TIMEOUT" })
    $duplicateSuccessfulSeats = @($successRows | Group-Object seatId | Where-Object { $_.Count -gt 1 })
    $duplicateOrderKeys = @($successRows | Where-Object { -not [string]::IsNullOrWhiteSpace($_.orderKey) } | Group-Object orderKey | Where-Object { $_.Count -gt 1 })
    $technicalFailurePercent = if ($resultRows.Count -gt 0) { ($technicalRows.Count * 100.0) / $resultRows.Count } else { 0.0 }

    $gatlingRows = @()
    foreach ($index in Get-ChildItem -Path $RunDir -Recurse -Filter index.html -File -ErrorAction SilentlyContinue) {
        $stats = Read-GatlingRootStats -ReportPath $index.FullName
        if ($stats) { $gatlingRows += $stats }
    }
    $maxP99 = ($gatlingRows | Where-Object { $null -ne $_.P99Ms } | Measure-Object -Property P99Ms -Maximum).Maximum

    $summary = [pscustomobject]@{
        totalResults = $resultRows.Count
        success = $successRows.Count
        businessRejected = $businessRows.Count
        queueTimeout = $queueTimeoutRows.Count
        technicalFailures = $technicalRows.Count
        technicalFailurePercent = [Math]::Round($technicalFailurePercent, 4)
        duplicateSuccessfulSeats = $duplicateSuccessfulSeats.Count
        duplicateOrderKeys = $duplicateOrderKeys.Count
        maxNodeP99Ms = $maxP99
        failedJobs = @($JobRows | Where-Object { $_.ExitCode -ne 0 }).Count
    }

    $summaryPath = Join-Path $RunDir "booking-summary.json"
    $summary | ConvertTo-Json | Set-Content -Path $summaryPath -Encoding UTF8
    $resultRows | Export-Csv -Path (Join-Path $RunDir "booking-results-merged.csv") -NoTypeInformation -Encoding UTF8

    Write-Host "Booking summary: $summaryPath"
    Write-Host "Total results: $($summary.totalResults), success: $($summary.success), businessRejected: $($summary.businessRejected), queueTimeout: $($summary.queueTimeout), technicalFailures: $($summary.technicalFailures)"

    $failed = $false
    if ($summary.failedJobs -gt 0) { Write-Warning "One or more nodes failed"; $failed = $true }
    if ($summary.technicalFailurePercent -ge $TechnicalFailureThresholdPercent) { Write-Warning "Technical failure threshold exceeded: $($summary.technicalFailurePercent)%"; $failed = $true }
    if ($summary.duplicateSuccessfulSeats -gt 0 -or $summary.duplicateOrderKeys -gt 0) { Write-Warning "Duplicate successful seat/order detected"; $failed = $true }
    if ($null -ne $maxP99) {
        $threshold = if ((Get-BookingScenario) -eq "TICKET_OPEN_END_TO_END") { [Math]::Max($QueueP99ThresholdMs, $TicketP99ThresholdMs) } else { $TicketP99ThresholdMs }
        if ($maxP99 -gt $threshold) { Write-Warning "Node p99 threshold exceeded: $maxP99 ms > $threshold ms"; $failed = $true }
    }
    return -not $failed
}

try {
    Assert-RemoteUrl -Name "CoreBaseUrl" -Value $CoreBaseUrl
    if ((Get-BookingScenario) -eq "TICKET_OPEN_END_TO_END") { Assert-RemoteUrl -Name "QueueBaseUrl" -Value $QueueBaseUrl }
    if ($RpsPerNode -le 0) { Stop-Validation "RpsPerNode must be positive" }
    if ($DurationSeconds -le 0) { Stop-Validation "DurationSeconds must be positive" }
    if ($InjectionMode -notin @("constant-users-per-sec", "ramp-users-per-sec", "ramp-users", "at-once-users")) { Stop-Validation "Unsupported InjectionMode: $InjectionMode" }

    $Hosts = Normalize-Hosts -Values $Hosts
    if ($Hosts.Count -eq 0) { Stop-Validation "At least one SSH host is required" }
    $feederRows = Import-BookingFeederRows -Path $FeederFile

    $startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
    $runDir = New-UniqueRunDirectoryPath -Root $ReportRoot -Name (New-RunDirectoryName)
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $manifestRows = New-NodeFeeders -Rows $feederRows -TotalNodes $Hosts.Count -RunDir $runDir
    $knownHostsFile = Join-Path $runDir "known_hosts"
    New-Item -ItemType File -Force -Path $knownHostsFile | Out-Null

    $windowsRoot = if ([string]::IsNullOrWhiteSpace($env:SystemRoot)) { "C:\Windows" } else { $env:SystemRoot }
    $SshCommand = Resolve-CommandPath -Name "ssh" -Candidates @((Join-Path $windowsRoot "System32\OpenSSH\ssh.exe"), (Join-Path $windowsRoot "Sysnative\OpenSSH\ssh.exe"))
    $ScpCommand = Resolve-CommandPath -Name "scp" -Candidates @((Join-Path $windowsRoot "System32\OpenSSH\scp.exe"), (Join-Path $windowsRoot "Sysnative\OpenSSH\scp.exe"))
    $TarCommand = Resolve-CommandPath -Name "tar" -Candidates @((Join-Path $windowsRoot "System32\tar.exe"), (Join-Path $windowsRoot "Sysnative\tar.exe"))
    $KeyPath = Resolve-DefaultSshKeyPath -Value $KeyPath
    if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf)) { Stop-Validation "SSH key not found: $KeyPath" }
    $KeyPath = New-OpenSshKeyPath -SourcePath $KeyPath
    $SshOptions = New-SshOptions -KnownHostsFile $knownHostsFile
    $ScpOptions = New-ScpOptions -KnownHostsFile $knownHostsFile

    Write-Host "Starting distributed booking Gatling run"
    Write-Host "Simulation: $Simulation"
    Write-Host "Scenario: $(Get-BookingScenario)"
    Write-Host "Remote nodes: $($Hosts.Count)"
    Write-Host "Global RPS: $($RpsPerNode * $Hosts.Count)"
    Write-Host "Run dir: $runDir"

    if (-not $SkipSyncProject) {
        $archive = New-ProjectArchive -RunDir $runDir
        foreach ($hostName in $Hosts) { Sync-RemoteProject -HostName $hostName -ArchivePath $archive -StartedAt $startedAt }
    }
    if (-not $SkipPreflight) {
        foreach ($hostName in $Hosts) { Test-RemoteProject -HostName $hostName }
    }

    $jobs = @()
    for ($nodeIndex = 0; $nodeIndex -lt $Hosts.Count; $nodeIndex++) {
        $hostName = $Hosts[$nodeIndex]
        $safeName = New-SafeNodeName $hostName
        $nodeRunRoot = "load-tests/gatling/build/distributed-booking/$startedAt/node-$nodeIndex"
        $remoteFeeder = "$nodeRunRoot/booking-feeder.csv"
        $remoteResult = "$nodeRunRoot/booking-results.csv"
        $remoteCollect = "$nodeRunRoot/collect"
        $localFeeder = $manifestRows[$nodeIndex].feederFile
        $logPath = Join-Path $runDir "$safeName.log"

        $remotePrepare = "timeout 30s bash -lc 'set -e; mkdir -p $RemoteProjectDir/$nodeRunRoot'"
        & $SshCommand @SshOptions $hostName $remotePrepare
        if ($LASTEXITCODE -ne 0) { throw "Remote feeder directory preparation failed for ${hostName}" }
        & $ScpCommand @ScpOptions $localFeeder "${hostName}:$RemoteProjectDir/$remoteFeeder"
        if ($LASTEXITCODE -ne 0) { throw "Feeder upload failed for ${hostName}" }
        $remoteCommand = New-RemoteCommand -NodeIndex $nodeIndex -CollectDir $remoteCollect -RemoteFeederFile $remoteFeeder -RemoteResultFile $remoteResult

        $jobs += Start-Job -Name $safeName -ScriptBlock {
            param($SshCommand, $SshOptions, $HostName, $Command, $LogPath)
            & $SshCommand @SshOptions $HostName $Command *> $LogPath
            return $LASTEXITCODE
        } -ArgumentList $SshCommand, $SshOptions, $hostName, $remoteCommand, $logPath
    }

    Wait-Job $jobs | Out-Null
    $jobRows = @()
    foreach ($job in $jobs) {
        $exitCode = Receive-Job $job
        $jobRows += [pscustomobject]@{ Node = $job.Name; ExitCode = $exitCode }
    }
    Remove-Job $jobs

    for ($nodeIndex = 0; $nodeIndex -lt $Hosts.Count; $nodeIndex++) {
        $hostName = $Hosts[$nodeIndex]
        $safeName = New-SafeNodeName $hostName
        $targetDir = Join-Path $runDir $safeName
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        $remoteCollect = "load-tests/gatling/build/distributed-booking/$startedAt/node-$nodeIndex/collect"
        & $ScpCommand @ScpOptions -r "${hostName}:$RemoteProjectDir/$remoteCollect" $targetDir
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not collect booking result/report files from ${hostName}" }
    }

    $passed = Write-BookingSummary -RunDir $runDir -JobRows $jobRows
    if (-not $passed) { exit 1 }
    exit 0
} catch {
    Write-Error $_
    exit 1
}