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
    [string]$Simulation = "",
    [string]$CoreBaseUrl = "",
    [string]$QueueBaseUrl = "",
    [int]$PerformanceId = 1,
    [string]$FeederFile = "build/booking-feeder.csv",
    [int]$FeederOffset = 0,
    [int]$RpsPerNode = 10,
    [int]$TargetRpsPerNode = 0,
    [int]$ConcurrentUsersPerNode = 0,
    [int]$FeederRowsPerNode = 0,
    [int]$DurationSeconds = 300,
    [string]$InjectionMode = "constant-users-per-sec",
    [int]$PollingTimeoutSeconds = 300,
    [int]$StatusPollPauseSeconds = 1,
    [int]$StatusPollPauseJitterSeconds = 0,
    [switch]$SkipSyncProject,
    [switch]$SkipPreflight,
    [switch]$CleanupRemote,
    [string]$LocalProjectDir = (Join-Path $PSScriptRoot "."),
    [string]$ReportRoot = (Join-Path $PSScriptRoot "distributed-results-join"),
    [double]$TechnicalFailureThresholdPercent = 1.0,
    [double]$QueueTimeoutThresholdPercent = 0.0,
    [int]$MaxCoreAdmissionsPerSecond = 0,
    [double]$AdmissionRateTolerancePercent = 10.0,
    [switch]$DbAuditEnabled
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = [Text.UTF8Encoding]::new($false)

. (Join-Path $PSScriptRoot 'scripts/distributed-common.ps1')

function Write-TextUtf8NoBom {
    param([string]$Path, [string]$Text)
    [IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Write-CsvUtf8NoBom {
    param([string]$Path, [object[]]$Rows)
    $lines = @($Rows | ConvertTo-Csv -NoTypeInformation)
    [IO.File]::WriteAllLines($Path, $lines, $Utf8NoBom)
}

function Stop-Validation {
    param([string]$Message)
    Write-Error $Message
    exit 2
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

function Get-BookingScenario {
    if ($Simulation -like "*TicketOpenEndToEndSimulation") { return "TICKET_OPEN_END_TO_END" }
    if ($Simulation -like "*SmokeSimulation") { return "SMOKE" }
    if ($Simulation -like "*HotSeatConcurrencySimulation") { return "HOT_SEAT_CONCURRENCY" }
    if ($Simulation -like "*CoreAdmissionCapacitySimulation") { return "CORE_ADMISSION_CAPACITY" }
    if ($Simulation -like "*CoreActiveUsersClosedSimulation") { return "CORE_ACTIVE_USERS_CLOSED" }
    if ($Simulation -like "*CoreSpikeSimulation") { return "CORE_SPIKE" }
    if ($Simulation -like "*QueueProtectsCoreSimulation") { return "QUEUE_PROTECTS_CORE" }
    Stop-Validation "Unsupported booking simulation: $Simulation"
}

function Get-SimulationRunName {
    return (Get-BookingScenario).ToLowerInvariant().Replace("_", "-")
}

function Get-EffectiveTargetRpsPerNode {
    if ($TargetRpsPerNode -gt 0) { return $TargetRpsPerNode }
    return $RpsPerNode
}

function Test-ClosedScenario {
    return (Get-BookingScenario) -eq "CORE_ACTIVE_USERS_CLOSED"
}

function Get-EffectiveInjectionMode {
    if (Test-ClosedScenario) { return "closed-core" }
    if ((Get-BookingScenario) -eq "CORE_SPIKE") { return "spike" }
    return $InjectionMode
}

function Get-ExpectedUsersPerNode {
    if (Test-ClosedScenario) {
        return $FeederRowsPerNode
    }

    $targetRps = Get-EffectiveTargetRpsPerNode
    $expectedUsers = switch (Get-EffectiveInjectionMode) {
        "at-once-users" { $RpsPerNode }
        "ramp-users-per-sec" { [Math]::Ceiling((($RpsPerNode + $targetRps) / 2.0) * $DurationSeconds) }
        "spike" {
            $totalUsers = ($RpsPerNode * 30) + ((($RpsPerNode + $targetRps) / 2.0) * 5) + ($targetRps * $DurationSeconds) + ((($targetRps + $RpsPerNode) / 2.0) * 5) + ($RpsPerNode * 30)
            [Math]::Ceiling($totalUsers)
        }
        default { [Math]::Ceiling($RpsPerNode * $DurationSeconds) }
    }
    return [int]$expectedUsers
}

function Get-InjectionUsersPerNode {
    if (Test-ClosedScenario) { return $ConcurrentUsersPerNode }
    return Get-ExpectedUsersPerNode
}

function Test-QueueScenario {
    return (Get-BookingScenario) -in @("TICKET_OPEN_END_TO_END", "QUEUE_PROTECTS_CORE")
}

function Test-ContentionScenario {
    return (Get-BookingScenario) -eq "HOT_SEAT_CONCURRENCY"
}

function Test-DynamicSeatScenario {
    return (Get-BookingScenario) -in @("CORE_ACTIVE_USERS_CLOSED", "CORE_SPIKE")
}

function Test-ProofScenario {
    return (Get-BookingScenario) -in @(
        "SMOKE",
        "HOT_SEAT_CONCURRENCY",
        "CORE_ADMISSION_CAPACITY",
        "CORE_ACTIVE_USERS_CLOSED",
        "CORE_SPIKE",
        "QUEUE_PROTECTS_CORE"
    )
}
function Import-BookingFeederRows {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Validation "FeederFile not found: $Path" }
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).ProviderPath)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Stop-Validation "FeederFile must be UTF-8 without BOM: $Path"
    }
    $lines = [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).ProviderPath, [Text.Encoding]::UTF8)
    $fixedSeatHeader = "memberId,accessToken,seatId,admissionToken"
    $dynamicSeatHeader = "memberId,accessToken,admissionToken"
    $dynamicSeatInput = $lines.Count -gt 0 -and $lines[0] -eq $dynamicSeatHeader
    if ($lines.Count -eq 0 -or ($lines[0] -ne $fixedSeatHeader -and
            -not ($dynamicSeatInput -and (Test-DynamicSeatScenario)))) {
        Stop-Validation "FeederFile header is invalid for scenario $(Get-BookingScenario)"
    }
    $script:BookingFeederHeader = $lines[0]
    $rows = New-Object System.Collections.Generic.List[string]
    $members = @{}
    $seats = @{}
    for ($index = 1; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $columns = $line.Split(",")
        $expectedColumns = if ($dynamicSeatInput) { 3 } else { 4 }
        if ($columns.Count -ne $expectedColumns) {
            Stop-Validation "Invalid feeder row $($index + 1): exactly $expectedColumns columns are required"
        }
        $memberId = 0L
        $seatId = 0L
        if (-not [long]::TryParse($columns[0].Trim(), [ref]$memberId) -or $memberId -le 0) { Stop-Validation "Invalid feeder row $($index + 1): memberId must be positive" }
        if (-not $dynamicSeatInput -and
                (-not [long]::TryParse($columns[2].Trim(), [ref]$seatId) -or $seatId -le 0)) {
            Stop-Validation "Invalid feeder row $($index + 1): seatId must be positive"
        }
        if ([string]::IsNullOrWhiteSpace($columns[1])) { Stop-Validation "Invalid feeder row $($index + 1): accessToken is required" }
        $admissionTokenIndex = if ($dynamicSeatInput) { 2 } else { 3 }
        if ((Get-BookingScenario) -notin @("TICKET_OPEN_END_TO_END", "QUEUE_PROTECTS_CORE", "CORE_ADMISSION_CAPACITY") -and
                [string]::IsNullOrWhiteSpace($columns[$admissionTokenIndex])) {
            Stop-Validation "Invalid feeder row $($index + 1): admissionToken is required"
        }
        if ($members.ContainsKey($memberId)) { Stop-Validation "Invalid feeder row $($index + 1): memberId must be unique" }
        $members[$memberId] = $true
        if (-not $dynamicSeatInput -and -not (Test-ContentionScenario) -and $seats.ContainsKey($seatId)) {
            Stop-Validation "Invalid feeder row $($index + 1): seatId must be unique"
        }
        if (-not $dynamicSeatInput) { $seats[$seatId] = $true }
        $rows.Add($line)
    }
    if ((Get-BookingScenario) -eq "HOT_SEAT_CONCURRENCY" -and $seats.Count -ne 1) {
        Stop-Validation "HOT_SEAT_CONCURRENCY requires every feeder row to use the same seatId"
    }
    return $rows
}

function New-NodeFeeders {
    param([System.Collections.Generic.List[string]]$Rows, [int]$TotalNodes, [string]$RunDir)
    $rowsPerNode = Get-ExpectedUsersPerNode
    $requiredRows = [int]($rowsPerNode * $TotalNodes)
    $requiredRowsWithOffset = [long]$FeederOffset + $requiredRows
    if ($Rows.Count -lt $requiredRowsWithOffset) {
        Stop-Validation "FeederFile has fewer rows than required for offset: offset=$FeederOffset, expected=$requiredRows, required=$requiredRowsWithOffset, actual=$($Rows.Count), rowsPerNode=$rowsPerNode, nodes=$TotalNodes"
    }

    $feederDir = Join-Path $RunDir "feeders"
    New-Item -ItemType Directory -Force -Path $feederDir | Out-Null
    $manifestRows = @()
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    $closedScenario = Test-ClosedScenario
    $manifestNodeRps = if ($closedScenario) { 0 } else { $RpsPerNode }
    $manifestTargetRps = if ($closedScenario) { 0 } else { Get-EffectiveTargetRpsPerNode }


    for ($nodeIndex = 0; $nodeIndex -lt $TotalNodes; $nodeIndex++) {
        $start = [int]($FeederOffset + ($nodeIndex * $rowsPerNode))
        $endExclusive = [int]($start + $rowsPerNode)
        $nodeRows = @($script:BookingFeederHeader) + @($Rows[$start..($endExclusive - 1)])
        $nodeFile = Join-Path $feederDir "booking-feeder-node-$nodeIndex.csv"
        [IO.File]::WriteAllLines($nodeFile, $nodeRows, $utf8NoBom)
        $manifestRows += [pscustomobject]@{
            nodeIndex = $nodeIndex
            totalNodes = $TotalNodes
            globalRps = $manifestNodeRps * $TotalNodes
            nodeRps = $manifestNodeRps
            globalTargetRps = $manifestTargetRps * $TotalNodes
            nodeTargetRps = $manifestTargetRps
            globalConcurrentUsers = $(if ($closedScenario) { $ConcurrentUsersPerNode * $TotalNodes } else { 0 })
            nodeConcurrentUsers = $(if ($closedScenario) { $ConcurrentUsersPerNode } else { 0 })
            injectionMode = Get-EffectiveInjectionMode
            feederOffset = $FeederOffset
            rowStart = $start + 1
            rowEnd = $endExclusive
            feederFile = $nodeFile
        }
    }

    $manifestPath = Join-Path $RunDir "manifest.csv"
    Write-CsvUtf8NoBom -Path $manifestPath -Rows $manifestRows
    return $manifestRows
}

function New-RunDirectoryName {
    if (Test-ClosedScenario) { return "$(Get-SimulationRunName)_pid$PerformanceId`_cu$ConcurrentUsersPerNode`_nodes$($Hosts.Count)`_dur${DurationSeconds}s" }
    $name = "$(Get-SimulationRunName)_pid$PerformanceId`_rps$RpsPerNode`_nodes$($Hosts.Count)`_dur${DurationSeconds}s"
    return ($name -replace "[^A-Za-z0-9._-]", "-").Trim("._-")
}

function New-GatlingArgs {
    param([int]$NodeIndex, [string]$RemoteFeederFile, [string]$RemoteResultFile)
    $args = @(
        "-p", "load-tests/gatling",
        "gatlingRun",
        "--simulation", $Simulation,
        "-DconsoleRunId=$ConsoleRunId",
        "-DcoreBaseUrl=$CoreBaseUrl",
        "-DqueueBaseUrl=$QueueBaseUrl",
        "-DperformanceId=$PerformanceId",
        "-DbookingFeederFile=$RemoteFeederFile",
        "-DbookingFeederOffset=0",
        "-DbookingScenario=$(Get-BookingScenario)",
        "-DnodeIndex=$NodeIndex",
        "-DresultFile=$RemoteResultFile",
        "-Dusers=$(Get-InjectionUsersPerNode)",
        "-DbookingFeederRows=$(Get-ExpectedUsersPerNode)",
        "-DdurationSeconds=$DurationSeconds",
        "-DinjectionMode=$(Get-EffectiveInjectionMode)",
        "-DusersPerSecond=$RpsPerNode",
        "-DtargetUsersPerSecond=$(Get-EffectiveTargetRpsPerNode)",
        "-DpollingTimeoutSeconds=$PollingTimeoutSeconds",
        "-DstatusPollPauseSeconds=$StatusPollPauseSeconds",
        "-DstatusPollPauseJitterSeconds=$StatusPollPauseJitterSeconds",
        "-DtechnicalFailureThresholdPercent=$TechnicalFailureThresholdPercent",
        "-DqueueTimeoutThresholdPercent=$QueueTimeoutThresholdPercent",
        "-DmaxCoreAdmissionsPerSecond=0",
        "-DadmissionRateTolerancePercent=$AdmissionRateTolerancePercent",
        "-DdbAuditEnabled=false"
    )
    if (-not [string]::IsNullOrWhiteSpace($RunDescription)) {
        $args += @("--run-description", $RunDescription)
    }
    return $args
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
latest=`$(ls -td distributed-results-join/*/ 2>/dev/null | head -1)
rm -rf '$CollectDir'
mkdir -p '$CollectDir'
if [ -n "`$latest" ]; then cp -R "`$latest" '$CollectDir/gatling-report'; fi
if [ -f '$RemoteResultFile' ]; then cp '$RemoteResultFile' '$CollectDir/booking-results.csv'; fi
evidence_dir=`$(dirname '$RemoteResultFile')
for f in booking-evidence.json booking-admissions.csv booking-completions.csv booking-active-users.csv booking-run-config.json; do
  if [ -f "`$evidence_dir/`$f" ]; then cp "`$evidence_dir/`$f" '$CollectDir'/"`$f"; fi
done
$cleanup
exit `$status
"@
    return (($command -replace "`r`n", "`n") -replace "`r", "`n")
}

function Merge-PerSecondCounts {
    param([string]$RunDir, [string]$Filter, [string]$Output)
    $rows = @()
    foreach ($file in @(Get-ChildItem -Path $RunDir -Recurse -Filter $Filter -File -ErrorAction SilentlyContinue)) { $rows += @(Import-Csv -Path $file.FullName) }
    $globalRows = @($rows |
        Group-Object epochSecond |
        ForEach-Object {
            [pscustomobject]@{
                epochSecond = [long]$_.Name
                count = [long](($_.Group | Measure-Object -Property count -Sum).Sum)
            }
        } |
        Sort-Object epochSecond)
    Write-CsvUtf8NoBom -Path (Join-Path $RunDir $Output) -Rows $globalRows
    return [long]($globalRows | Measure-Object -Property count -Maximum).Maximum
}

function Write-BookingSummary {
    param([string]$RunDir, [object[]]$JobRows)
    $resultFiles = @(Get-ChildItem -Path $RunDir -Recurse -Filter booking-results.csv -File -ErrorAction SilentlyContinue)
    $resultRows = @()
    foreach ($file in $resultFiles) { $resultRows += @(Import-Csv -Path $file.FullName) }

    $evidenceFiles = @(Get-ChildItem -Path $RunDir -Recurse -Filter booking-evidence.json -File -ErrorAction SilentlyContinue)
    $evidenceRows = @()
    foreach ($file in $evidenceFiles) {
        try { $evidenceRows += (Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json) }
        catch { Write-Warning "Could not read booking evidence: $($file.FullName)" }
    }
    $startedUsers = [long]($evidenceRows | Measure-Object -Property startedUsers -Sum).Sum
    $terminalUsers = [long]($evidenceRows | Measure-Object -Property terminalUsers -Sum).Sum
    $reportedMissingResults = [long]($evidenceRows | Measure-Object -Property missingTerminalResults -Sum).Sum
    $seatSelectionConflictAttempts = [long]($evidenceRows | Measure-Object -Property seatSelectionConflictAttempts -Sum).Sum
    $missingTerminalResults = [Math]::Max($reportedMissingResults, $startedUsers - $resultRows.Count)

    $maxObservedCoreAdmissions = Merge-PerSecondCounts -RunDir $RunDir -Filter booking-admissions.csv -Output booking-admissions-global.csv
    $maxObservedSuccessfulCompletions = Merge-PerSecondCounts -RunDir $RunDir -Filter booking-completions.csv -Output booking-completions-global.csv

    $activeFiles = @(Get-ChildItem -Path $RunDir -Recurse -Filter booking-active-users.csv -File -ErrorAction SilentlyContinue)
    $activeEvents = @()
    foreach ($file in $activeFiles) {
        foreach ($row in @(Import-Csv -Path $file.FullName)) {
            $activeEvents += [pscustomobject]@{
                node = $file.Directory.FullName
                epochMilli = [long]$row.epochMilli
                activeUsers = [long]$row.activeUsers
            }
        }
    }
    $nodeActiveUsers = @{}
    $globalActiveRows = @()
    foreach ($timeGroup in @($activeEvents | Group-Object epochMilli | Sort-Object { [long]$_.Name })) {
        foreach ($event in $timeGroup.Group) { $nodeActiveUsers[$event.node] = $event.activeUsers }
        $totalActiveUsers = [long]($nodeActiveUsers.Values | Measure-Object -Sum).Sum
        $globalActiveRows += [pscustomobject]@{ epochMilli = [long]$timeGroup.Name; activeUsers = $totalActiveUsers }
    }
    Write-CsvUtf8NoBom -Path (Join-Path $RunDir "booking-active-users-global.csv") -Rows $globalActiveRows
    $maxObservedActiveUsers = [long]($globalActiveRows | Measure-Object -Property activeUsers -Maximum).Maximum

    $allowedCoreAdmissions = if ($MaxCoreAdmissionsPerSecond -gt 0) {
        [Math]::Ceiling($MaxCoreAdmissionsPerSecond * (1.0 + $AdmissionRateTolerancePercent / 100.0))
    } else { 0 }

    $successRows = @($resultRows | Where-Object { $_.result -eq "SUCCESS" })
    $businessRows = @($resultRows | Where-Object { $_.result -like "BUSINESS_REJECTED_*" })
    # OVERLOADED_*: Core가 E6003(선점 락 대기 초과)으로 거절한 사용자. 비즈니스 거절도 기술 실패도 아니라 따로 센다.
    $overloadedRows = @($resultRows | Where-Object { $_.result -like "OVERLOADED_*" })
    $dropoutRows = @($resultRows | Where-Object { $_.result -like "USER_DROPPED_*" })
    $queueTimeoutRows = @($resultRows | Where-Object { $_.result -eq "QUEUE_TIMEOUT" })
    $technicalRows = @($resultRows | Where-Object { $_.result -ne "SUCCESS" -and $_.result -notlike "BUSINESS_REJECTED_*" -and $_.result -notlike "OVERLOADED_*" -and $_.result -notlike "USER_DROPPED_*" -and $_.result -ne "QUEUE_TIMEOUT" })
    $duplicateTerminalMembers = @($resultRows | Group-Object memberId | Where-Object { $_.Count -gt 1 })
    $duplicateSuccessfulSeats = @($successRows | Group-Object seatId | Where-Object { $_.Count -gt 1 })
    $duplicateOrderKeys = @($successRows | Where-Object { -not [string]::IsNullOrWhiteSpace($_.orderKey) } | Group-Object orderKey | Where-Object { $_.Count -gt 1 })
    $technicalFailureDenominator = if ($startedUsers -gt 0) { $startedUsers } else { $resultRows.Count }
    $technicalFailurePercent = if ($technicalFailureDenominator -gt 0) { ($technicalRows.Count * 100.0) / $technicalFailureDenominator } else { 0.0 }
    $queueTimeoutDenominator = if ($startedUsers -gt 0) { $startedUsers } else { $resultRows.Count }
    $queueTimeoutPercent = if ($queueTimeoutDenominator -gt 0) { ($queueTimeoutRows.Count * 100.0) / $queueTimeoutDenominator } else { 0.0 }

    $gatlingRows = @()
    foreach ($index in Get-ChildItem -Path $RunDir -Recurse -Filter index.html -File -ErrorAction SilentlyContinue) {
        $stats = Read-GatlingRootStats -ReportPath $index.FullName
        if ($stats) { $gatlingRows += $stats }
    }
    $maxP99 = ($gatlingRows | Where-Object { $null -ne $_.P99Ms } | Measure-Object -Property P99Ms -Maximum).Maximum

    $summary = [pscustomobject]@{
        evidenceFiles = $evidenceRows.Count
        expectedEvidenceFiles = $JobRows.Count
        startedUsers = [long]$startedUsers
        terminalUsers = [long]$terminalUsers
        totalResults = $resultRows.Count
        missingTerminalResults = [long]$missingTerminalResults
        duplicateTerminalMembers = $duplicateTerminalMembers.Count
        success = $successRows.Count
        businessRejected = $businessRows.Count
        overloaded = $overloadedRows.Count
        userDropped = $dropoutRows.Count
        seatSelectionConflictAttempts = [long]$seatSelectionConflictAttempts
        queueTimeout = $queueTimeoutRows.Count
        queueTimeoutPercent = [Math]::Round($queueTimeoutPercent, 4)
        queueTimeoutThresholdPercent = $QueueTimeoutThresholdPercent
        technicalFailures = $technicalRows.Count
        technicalFailurePercent = [Math]::Round($technicalFailurePercent, 4)
        technicalFailureThresholdPercent = $TechnicalFailureThresholdPercent
        duplicateSuccessfulSeats = $duplicateSuccessfulSeats.Count
        duplicateOrderKeys = $duplicateOrderKeys.Count
        maxObservedCoreAdmissionsPerSecond = [long]$maxObservedCoreAdmissions
        maxObservedSuccessfulCompletionsPerSecond = [long]$maxObservedSuccessfulCompletions
        maxObservedActiveUsers = [long]$maxObservedActiveUsers
        configuredMaxCoreAdmissionsPerSecond = $MaxCoreAdmissionsPerSecond
        allowedCoreAdmissionsPerSecond = [long]$allowedCoreAdmissions
        maxNodeP99Ms = $maxP99
        failedJobs = @($JobRows | Where-Object { $_.ExitCode -ne 0 }).Count
    }

    $summaryPath = Join-Path $RunDir "booking-summary.json"
    Write-TextUtf8NoBom -Path $summaryPath -Text ($summary | ConvertTo-Json)
    Write-CsvUtf8NoBom -Path (Join-Path $RunDir "booking-results-merged.csv") -Rows $resultRows

    Write-Host "Booking summary: $summaryPath"
    Write-Host "Started: $($summary.startedUsers), terminal: $($summary.terminalUsers), missing: $($summary.missingTerminalResults), queueTimeout: $($summary.queueTimeout), overloaded: $($summary.overloaded), maxCoreAdmissions/s: $($summary.maxObservedCoreAdmissionsPerSecond)"

    $failed = $false
    if ($summary.failedJobs -gt 0) { Write-Warning "One or more nodes failed"; $failed = $true }
    if (Test-ProofScenario) {
        if ($summary.evidenceFiles -ne $summary.expectedEvidenceFiles) { Write-Warning "Booking evidence file count mismatch"; $failed = $true }
        if ($summary.missingTerminalResults -ne 0 -or $summary.terminalUsers -ne $summary.totalResults) { Write-Warning "Booking result completeness failed"; $failed = $true }
    }
    if ($summary.duplicateTerminalMembers -gt 0) { Write-Warning "Duplicate terminal result detected"; $failed = $true }
    if ($summary.queueTimeoutPercent -gt $QueueTimeoutThresholdPercent) { Write-Warning "Queue timeout threshold exceeded: $($summary.queueTimeoutPercent)%"; $failed = $true }
    if ($summary.technicalFailurePercent -ge $TechnicalFailureThresholdPercent) { Write-Warning "Technical failure threshold exceeded: $($summary.technicalFailurePercent)%"; $failed = $true }
    if ($summary.duplicateSuccessfulSeats -gt 0 -or $summary.duplicateOrderKeys -gt 0) { Write-Warning "Duplicate successful seat/order detected"; $failed = $true }
    if ((Get-BookingScenario) -eq "HOT_SEAT_CONCURRENCY" -and ($summary.success -ne 1 -or ($summary.businessRejected + $summary.overloaded) -ne ($summary.totalResults - 1))) {
        Write-Warning "Hot-seat invariant failed: expected one success and all remaining users business-rejected or overloaded"
        $failed = $true
    }
    if ((Get-BookingScenario) -eq "QUEUE_PROTECTS_CORE") {
        if ($MaxCoreAdmissionsPerSecond -le 0) { Write-Warning "MaxCoreAdmissionsPerSecond must be positive for Queue protection proof"; $failed = $true }
        elseif ($summary.maxObservedCoreAdmissionsPerSecond -gt $allowedCoreAdmissions) { Write-Warning "Observed Core admission rate exceeded: $($summary.maxObservedCoreAdmissionsPerSecond)/s > $allowedCoreAdmissions/s"; $failed = $true }
    }
    return -not $failed
}

function Invoke-BookingDbAudit {
    param([string]$RunDir)
    if (-not $DbAuditEnabled) { return $true }
    $resultFile = Join-Path $RunDir "booking-results-merged.csv"
    $outputFile = Join-Path $RunDir "booking-db-audit.json"
    $gradleWrapper = Join-Path $LocalProjectDir $(if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { "gradlew.bat" } else { "gradlew" })
    & $gradleWrapper -p load-tests/gatling auditBookingDatabase "-DresultFile=$resultFile" "-DperformanceId=$PerformanceId" "-DdbAuditOutput=$outputFile"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Booking DB audit failed"
        return $false
    }
    return $true
}
try {
    Assert-RemoteUrl -Name "CoreBaseUrl" -Value $CoreBaseUrl
    if (Test-QueueScenario) { Assert-RemoteUrl -Name "QueueBaseUrl" -Value $QueueBaseUrl }
    if (Test-ClosedScenario) {
        if ($ConcurrentUsersPerNode -le 0) { Stop-Validation "ConcurrentUsersPerNode must be positive for the closed model" }
        if ($FeederRowsPerNode -lt $ConcurrentUsersPerNode) {
            Stop-Validation "FeederRowsPerNode must be at least ConcurrentUsersPerNode for the closed model"
        }
    } elseif ($RpsPerNode -le 0) {
        Stop-Validation "RpsPerNode must be positive"
    }
    if ($DurationSeconds -le 0) { Stop-Validation "DurationSeconds must be positive" }
    if ($FeederOffset -lt 0) { Stop-Validation "FeederOffset must be non-negative" }
    if ((Get-BookingScenario) -eq "CORE_ADMISSION_CAPACITY" -and (Get-EffectiveInjectionMode) -ne "constant-users-per-sec") { Stop-Validation "Core Admission Capacity requires constant-users-per-sec" }
    if ($TechnicalFailureThresholdPercent -le 0 -or $TechnicalFailureThresholdPercent -gt 100) { Stop-Validation "TechnicalFailureThresholdPercent must be greater than 0 and at most 100" }
    if ($QueueTimeoutThresholdPercent -lt 0) { Stop-Validation "QueueTimeoutThresholdPercent must be non-negative" }
    if ($MaxCoreAdmissionsPerSecond -lt 0) { Stop-Validation "MaxCoreAdmissionsPerSecond must be non-negative" }
    if ($AdmissionRateTolerancePercent -lt 0) { Stop-Validation "AdmissionRateTolerancePercent must be non-negative" }
    if ((Get-BookingScenario) -eq "QUEUE_PROTECTS_CORE" -and $MaxCoreAdmissionsPerSecond -le 0) { Stop-Validation "MaxCoreAdmissionsPerSecond must be positive for Queue protection proof" }
    if ($DbAuditEnabled -and ([string]::IsNullOrWhiteSpace($env:BOOKING_AUDIT_DB_URL) -or [string]::IsNullOrWhiteSpace($env:BOOKING_AUDIT_DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:BOOKING_AUDIT_DB_PASSWORD))) { Stop-Validation "DbAuditEnabled requires BOOKING_AUDIT_DB_URL, BOOKING_AUDIT_DB_USERNAME and BOOKING_AUDIT_DB_PASSWORD" }
    if ((Get-EffectiveInjectionMode) -notin @("constant-users-per-sec", "ramp-users-per-sec", "ramp-users", "at-once-users", "spike", "closed-core")) { Stop-Validation "Unsupported InjectionMode: $InjectionMode" }
    if ((Get-EffectiveInjectionMode) -eq "spike" -and (Get-EffectiveTargetRpsPerNode) -le $RpsPerNode) {
        Stop-Validation "Spike target RPS must be greater than baseline RPS"
    }

    $Hosts = Normalize-Hosts -Values $Hosts
    if ($Hosts.Count -eq 0) { Stop-Validation "At least one SSH host is required" }
    $feederRows = Import-BookingFeederRows -Path $FeederFile

    $startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
    $runDir = New-UniqueRunDirectoryPath -Root $ReportRoot -Name (New-RunDirectoryName)
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $manifestRows = New-NodeFeeders -Rows $feederRows -TotalNodes $Hosts.Count -RunDir $runDir
    $knownHostsFile = Join-Path $runDir "known_hosts"
    New-Item -ItemType File -Force -Path $knownHostsFile | Out-Null

    $SshCommand = Resolve-WindowsCommandPath -Name "ssh" -RelativePath "OpenSSH\ssh.exe"
    $ScpCommand = Resolve-WindowsCommandPath -Name "scp" -RelativePath "OpenSSH\scp.exe"
    $TarCommand = Resolve-WindowsCommandPath -Name "tar" -RelativePath "tar.exe"
    $KeyPath = Resolve-DefaultSshKeyPath -Value $KeyPath
    if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf)) { Stop-Validation "SSH key not found: $KeyPath" }
    $KeyPath = New-OpenSshKeyPath -SourcePath $KeyPath
    $SshOptions = New-SshOptions -KnownHostsFile $knownHostsFile
    $ScpOptions = New-ScpOptions -KnownHostsFile $knownHostsFile

    Write-Host "Starting distributed booking Gatling run"
    Write-Host "Simulation: $Simulation"
    Write-Host "Scenario: $(Get-BookingScenario)"
    Write-Host "Remote nodes: $($Hosts.Count)"
    if (Test-ClosedScenario) { Write-Host "Global concurrent users: $($ConcurrentUsersPerNode * $Hosts.Count)" } else { Write-Host "Global RPS: $($RpsPerNode * $Hosts.Count)" }
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

        $jobs += Start-RemoteNodeJob -Name $safeName -HostName $hostName -Command $remoteCommand -LogPath $logPath
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
    $dbAuditPassed = Invoke-BookingDbAudit -RunDir $runDir
    if (-not $passed -or -not $dbAuditPassed) { exit 1 }
    exit 0
} catch {
    Write-Error $_
    exit 1
}
