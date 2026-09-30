# Shared helpers dot-sourced by run-distributed-booking.ps1 and run-distributed-gatling-cdn.ps1.
# Kept ASCII-only except the OneDrive key path: Windows PowerShell 5.1 reads BOM-less files as ANSI.
# Functions read the caller's script variables: $KeyPath, $LocalProjectDir, $RemoteProjectDir,
# $SshCommand, $SshOptions, $ScpCommand, $ScpOptions, $TarCommand.

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
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }

    throw "Required command not found: $Name"
}

# Falls back to System32/Sysnative when the command is not on PATH. RelativePath e.g. "OpenSSH\ssh.exe"
function Resolve-WindowsCommandPath {
    param(
        [string]$Name,
        [string]$RelativePath
    )

    $windowsRoot = if ([string]::IsNullOrWhiteSpace($env:SystemRoot)) { "C:\Windows" } else { $env:SystemRoot }
    return Resolve-CommandPath -Name $Name -Candidates @(
        (Join-Path $windowsRoot "System32\$RelativePath"),
        (Join-Path $windowsRoot "Sysnative\$RelativePath"),
        "C:\Windows\System32\$RelativePath",
        "C:\Windows\Sysnative\$RelativePath"
    )
}

function Start-RemoteNodeJob {
    param(
        [string]$Name,
        [string]$HostName,
        [string]$Command,
        [string]$LogPath
    )

    return Start-Job -Name $Name -ScriptBlock {
        param($SshCommand, $SshOptions, $HostName, $Command, $LogPath)
        & $SshCommand @SshOptions $HostName $Command *> $LogPath
        return $LASTEXITCODE
    } -ArgumentList $SshCommand, $SshOptions, $HostName, $Command, $LogPath
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

function New-UniqueRunDirectoryPath {
    param(
        [string]$Root,
        [string]$Name
    )

    $candidate = Join-Path $Root $Name
    if (-not (Test-Path -LiteralPath $candidate)) {
        return $candidate
    }

    for ($index = 2; $index -lt 1000; $index++) {
        $candidate = Join-Path $Root "${Name}_$index"
        if (-not (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }
    throw "Could not create unique run directory under ${Root}: $Name"
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

function Resolve-DefaultSshKeyPath {
    param([string]$Value)

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        return $Value
    }

    $userRoot = if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        [Environment]::GetFolderPath("UserProfile")
    } else {
        $env:USERPROFILE
    }
    $candidates = @(
        (Join-Path $userRoot "OneDrive\바탕 화면\ticket\ticket-test-key-01.pem"),
        (Join-Path $userRoot "Desktop\ticket\ticket-test-key-01.pem")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    return $candidates[-1]
}

function New-OpenSshKeyPath {
    param([string]$SourcePath)

    $resolvedPath = (Resolve-Path -LiteralPath $SourcePath).ProviderPath
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        return $resolvedPath
    }

    $keyRoot = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        Join-Path ([IO.Path]::GetTempPath()) "ticket-gatling\ssh-keys"
    } else {
        Join-Path $env:LOCALAPPDATA "ticket-gatling\ssh-keys"
    }
    New-Item -ItemType Directory -Force -Path $keyRoot | Out-Null

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($resolvedPath.ToLowerInvariant()))
    } finally {
        $sha256.Dispose()
    }
    $hash = -join ($hashBytes | ForEach-Object { $_.ToString("x2") })
    $targetPath = Join-Path $keyRoot ("ssh-key-" + $hash.Substring(0, 16) + ".pem")

    Copy-Item -LiteralPath $resolvedPath -Destination $targetPath -Force

    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $icaclsCommand = Resolve-WindowsCommandPath -Name "icacls.exe" -RelativePath "icacls.exe"
    & $icaclsCommand $targetPath /inheritance:r | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to disable SSH key inheritance: $targetPath"
    }
    & $icaclsCommand $targetPath /grant:r "${currentUser}:F" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict SSH key permissions: $targetPath"
    }

    return $targetPath
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

function New-ProjectArchive {
    param([string]$RunDir)

    $archivePath = Join-Path $RunDir "gatling-test-project.tgz"
    $tarArgs = @(
        "-czf", $archivePath,
        "--exclude=.git",
        "--exclude=.gradle",
        "--exclude=.tmp",
        "--exclude=distributed-results-join",
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

function ConvertTo-SummaryNumber {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }

    $normalized = [System.Net.WebUtility]::HtmlDecode($Value).Trim().Replace(",", "")
    $number = 0.0
    if ([double]::TryParse(
            $normalized,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$number
        )) {
        return $number
    }
    return $null
}

function Read-GatlingRootStats {
    param([string]$ReportPath)

    if ([string]::IsNullOrWhiteSpace($ReportPath) -or -not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
        return $null
    }

    $html = Get-Content -LiteralPath $ReportPath -Raw -Encoding UTF8
    $rowMatch = [regex]::Match(
        $html,
        '<tr[^>]*id="ROOT"[^>]*>.*?</tr>',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    if (-not $rowMatch.Success) {
        return $null
    }

    $values = @{}
    foreach ($match in [regex]::Matches($rowMatch.Value, '<td class="value [^"]* col-(\d+)">([^<]*)</td>')) {
        $values[[int]$match.Groups[1].Value] = ConvertTo-SummaryNumber -Value $match.Groups[2].Value
    }

    return [pscustomobject]@{
        TotalRequests = $values[2]
        OkRequests = $values[3]
        KoRequests = $values[4]
        KoPercent = $values[5]
        RequestsPerSec = $values[6]
        MinMs = $values[7]
        P50Ms = $values[8]
        P75Ms = $values[9]
        P95Ms = $values[10]
        P99Ms = $values[11]
        MaxMs = $values[12]
        MeanMs = $values[13]
        StdDevMs = $values[14]
    }
}
