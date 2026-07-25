param(
    [string]$KeyPath = "",
    [string[]]$Hosts = @(
        "ubuntu@43.203.155.15",
        "ubuntu@15.165.40.25",
        "ubuntu@43.203.136.184"
    ),
    [string]$RemoteProjectDir = "~/gatling-test",
    [string]$ConsoleRunId = "",
    [string]$BaseUrl = "https://queue.oneticket.site",
    [string]$Simulation = "com.ticket.loadtest.simulation.QueueJoinOnlySimulation",
    [int]$PerformanceId = 1,
    [int]$RpsPerNode = 100,
    [int]$DurationSeconds = 300,
    [string]$InjectionMode = "constant-users-per-sec",
    [long]$StartAtEpochMillis = 0,
    [int]$StatusPolls = 1,
    [int]$StatusPollPauseSeconds = 0,
    [int]$StatusPollPauseJitterSeconds = 0,
    [switch]$EnableHttp2,
    [string]$AccessTokenMode = "tokens",
    [string]$AccessTokensFile = "",
    [switch]$GenerateAccessTokens,
    [int]$TokenCountPerNode = 0,
    [string]$JwtSecret = "0123456789abcdef0123456789abcdef",
    [string]$JwtIssuer = "ticket",
    [long]$SyntheticMemberStartId = 1,
    [string]$SyntheticJwtRole = "MEMBER",
    [int]$SyntheticTokenTtlSeconds = 3600,
    [switch]$DumpFailureBody,
    [int]$DumpFailureBodyLimit = 1,
    [switch]$SkipSyncProject,
    [switch]$SkipPreflight,
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
    BaseUrl = $BaseUrl
    Simulation = $Simulation
    PerformanceId = $PerformanceId
    RpsPerNode = $RpsPerNode
    DurationSeconds = $DurationSeconds
    InjectionMode = $InjectionMode
    StartAtEpochMillis = $StartAtEpochMillis
    StatusPolls = $StatusPolls
    StatusPollPauseSeconds = $StatusPollPauseSeconds
    StatusPollPauseJitterSeconds = $StatusPollPauseJitterSeconds
    AccessTokenMode = $AccessTokenMode
    AccessTokensFile = $AccessTokensFile
    TokenCountPerNode = $TokenCountPerNode
    JwtSecret = $JwtSecret
    JwtIssuer = $JwtIssuer
    SyntheticMemberStartId = $SyntheticMemberStartId
    SyntheticJwtRole = $SyntheticJwtRole
    SyntheticTokenTtlSeconds = $SyntheticTokenTtlSeconds
    DumpFailureBodyLimit = $DumpFailureBodyLimit
    SkipPreflight = $SkipPreflight
    LocalProjectDir = $LocalProjectDir
    ReportRoot = $ReportRoot
}

if (-not $SkipSyncProject) {
    $arguments.SyncProject = $true
}
if ($AccessTokenMode -eq "tokens" -and ($GenerateAccessTokens -or [string]::IsNullOrWhiteSpace($AccessTokensFile))) {
    $arguments.GenerateAccessTokens = $true
}
if ($IncludeLocal) {
    $arguments.IncludeLocal = $true
}
if ($EnableHttp2) {
    $arguments.EnableHttp2 = $true
}
if ($DumpFailureBody) {
    $arguments.DumpFailureBody = $true
}
if ($CollectReports) {
    $arguments.CollectReports = $true
}

& $runner @arguments
