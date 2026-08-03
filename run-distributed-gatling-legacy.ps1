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
    [string]$BaseUrl = "http://52.237.82.8:18090/legacy-queue",
    [string]$Simulation = "com.ticket.loadtest.simulation.LegacyQueueStatusSimulation",
    [int]$PerformanceId = 1,
    [int]$RpsPerNode = 100,
    [int]$DurationSeconds = 300,
    [int]$StatusPolls = 1,
    [int]$StatusPollPauseSeconds = 0,
    [int]$StatusPollPauseJitterSeconds = 0,
    [switch]$DumpFailureBody,
    [int]$DumpFailureBodyLimit = 1,
    [switch]$IncludeLocal,
    [switch]$CollectReports,
    [string]$LocalProjectDir = (Join-Path $PSScriptRoot "."),
    [string]$ReportRoot = (Join-Path $PSScriptRoot "distributed-results-join")
)

$runner = Join-Path $PSScriptRoot "run-distributed-gatling-cdn.ps1"
if (-not (Test-Path $runner)) {
    throw "Shared distributed Gatling runner not found: $runner"
}

$arguments = @{
    KeyPath = $KeyPath
    Hosts = $Hosts
    RemoteProjectDir = $RemoteProjectDir
    ConsoleRunId = $ConsoleRunId
    RunDescription = $RunDescription
    BaseUrl = $BaseUrl
    Simulation = $Simulation
    PerformanceId = $PerformanceId
    RpsPerNode = $RpsPerNode
    DurationSeconds = $DurationSeconds
    StatusPolls = $StatusPolls
    StatusPollPauseSeconds = $StatusPollPauseSeconds
    StatusPollPauseJitterSeconds = $StatusPollPauseJitterSeconds
    DumpFailureBodyLimit = $DumpFailureBodyLimit
    LocalProjectDir = $LocalProjectDir
    ReportRoot = $ReportRoot
}

if ($DumpFailureBody) {
    $arguments.DumpFailureBody = $true
}
if ($IncludeLocal) {
    $arguments.IncludeLocal = $true
}
if ($CollectReports) {
    $arguments.CollectReports = $true
}

& $runner @arguments
