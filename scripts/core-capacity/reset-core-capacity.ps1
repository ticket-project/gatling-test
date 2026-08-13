# 전용 Core Capacity 30개 회차의 Oracle 실행 이력과 Redis Hold를 한 번에 안전하게 원복한다.
[CmdletBinding()]
param(
    [string]$OracleUser,
    [string]$OracleDataSource,
    [Security.SecureString]$OraclePassword,
    [string]$SqlClientPath = 'sqlplus',
    [string]$RedisHost,
    [ValidateRange(1, 65535)][int]$RedisPort = 6379,
    [ValidateRange(0, 2147483647)][int]$RedisDatabase = 0,
    [string]$RedisUser,
    [Security.SecureString]$RedisPassword,
    [switch]$RedisNoAuthentication,
    [switch]$Tls,
    [string]$RedisCliPath = 'redis-cli',
    [string]$OutputDirectory = 'C:\loadtest\reset',
    [switch]$OperationalConfirmation,
    [string]$CoreStoppedConfirmation,
    [string]$DeleteConfirmation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function ConvertTo-ResetPlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureValue)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Assert-CoreCapacityPerformanceId {
    param([Parameter(Mandatory)][long]$PerformanceId)

    if ($PerformanceId -lt 910000001L -or $PerformanceId -gt 910000030L) {
        throw 'PerformanceId must be in the dedicated Core Capacity range 910000001..910000030.'
    }
}

function Read-CoreCapacityHoldKeys {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($fullPath)) {
        throw "Hold-key export does not exist: $fullPath"
    }

    $keys = @(
        [IO.File]::ReadAllLines($fullPath, [Text.Encoding]::UTF8) |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    foreach ($key in $keys) {
        if ($key -notmatch '^HOLD-[0-9a-fA-F]{32}$') {
            throw "Hold-key export contains an invalid value: $key"
        }
    }

    return @($keys | Sort-Object -Unique)
}

function Assert-CoreCapacityRedisKey {
    param(
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][long]$PerformanceId,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$HoldKeys
    )

    $escapedPerformanceId = [regex]::Escape([string]$PerformanceId)
    if ($Key -eq "seat:select:index:{perf:$PerformanceId}" -or
        $Key -eq "seat:hold:index:{perf:$PerformanceId}" -or
        $Key -match "^seat:(select|hold):\{perf:$escapedPerformanceId\}:\d+$") {
        return
    }

    if ($Key -match '^hold:key:(HOLD-[0-9a-fA-F]{32})$' -and $HoldKeys -contains $Matches[1]) {
        return
    }

    throw "Refusing Redis key outside the exact Core Capacity target: $Key"
}

function Invoke-CoreCapacityRedisReset {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('Inspect', 'Delete')][string]$Mode,
        [Parameter(Mandatory)][long]$PerformanceId,
        [Parameter(Mandatory)][string]$HoldKeysFile,
        [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$RedisHost,
        [ValidateRange(1, 65535)][int]$RedisPort = 6379,
        [ValidateRange(0, 2147483647)][int]$RedisDatabase = 0,
        [string]$RedisUser,
        [Security.SecureString]$RedisPassword,
        [switch]$Tls,
        [string]$RedisCliPath = 'redis-cli',
        [Parameter(Mandatory)][ValidateScript({ [IO.Path]::GetExtension($_) -eq '.json' })][string]$ResultFile,
        [switch]$OperationalConfirmation,
        [string]$DeleteConfirmation
    )

    Assert-CoreCapacityPerformanceId -PerformanceId $PerformanceId
    if (-not $OperationalConfirmation) {
        throw 'OperationalConfirmation is required before contacting Redis.'
    }

    $expectedConfirmation = "DELETE_CORE_CAPACITY_${PerformanceId}_REDIS_KEYS"
    if ($Mode -eq 'Delete' -and $DeleteConfirmation -cne $expectedConfirmation) {
        throw "DeleteConfirmation must exactly equal: $expectedConfirmation"
    }

    $resultPath = [IO.Path]::GetFullPath($ResultFile)
    if ([IO.File]::Exists($resultPath)) {
        throw "Result file already exists: $resultPath"
    }
    $resultDirectory = [IO.Path]::GetDirectoryName($resultPath)
    if (-not [IO.Directory]::Exists($resultDirectory)) {
        [IO.Directory]::CreateDirectory($resultDirectory) | Out-Null
    }

    $holdKeys = @(Read-CoreCapacityHoldKeys -Path $HoldKeysFile)
    $baseArguments = @('--raw', '--no-auth-warning', '-h', $RedisHost, '-p', [string]$RedisPort, '-n', [string]$RedisDatabase)
    if (-not [string]::IsNullOrWhiteSpace($RedisUser)) {
        $baseArguments += @('--user', $RedisUser)
    }
    if ($Tls) {
        $baseArguments += '--tls'
    }

    $previousAuth = $env:REDISCLI_AUTH
    $plainPassword = $null
    if ($null -ne $RedisPassword) {
        $plainPassword = ConvertTo-ResetPlainText -SecureValue $RedisPassword
        $env:REDISCLI_AUTH = $plainPassword
    }

    function Invoke-RedisCommand {
        param([Parameter(Mandatory)][string[]]$Arguments)

        $output = @(& $RedisCliPath @baseArguments @Arguments 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "redis-cli failed with exit code $LASTEXITCODE. Verify host, TLS, authentication and database selection."
        }
        if ($output.Count -gt 0 -and [string]$output[0] -match '^(NOAUTH|WRONGPASS|ERR)') {
            throw "Redis rejected the command: $($output[0])"
        }
        return @($output | ForEach-Object { [string]$_ })
    }

    function Find-RedisKeys {
        param([Parameter(Mandatory)][string]$Pattern)
        return @(Invoke-RedisCommand -Arguments @('--scan', '--pattern', $Pattern) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }

    function Get-TargetInventory {
        $keys = New-Object Collections.Generic.List[string]
        foreach ($pattern in @(
            "seat:select:{perf:$PerformanceId}:*",
            "seat:hold:{perf:$PerformanceId}:*"
        )) {
            foreach ($key in (Find-RedisKeys -Pattern $pattern)) {
                $keys.Add($key)
            }
        }

        foreach ($indexKey in @(
            "seat:select:index:{perf:$PerformanceId}",
            "seat:hold:index:{perf:$PerformanceId}"
        )) {
            $exists = @(Invoke-RedisCommand -Arguments @('EXISTS', $indexKey))
            if ($exists.Count -ne 1 -or $exists[0] -notmatch '^\d+$') {
                throw "Unexpected EXISTS response for $indexKey"
            }
            if ([int]$exists[0] -gt 0) {
                $keys.Add($indexKey)
            }
        }

        foreach ($holdKey in $holdKeys) {
            $metaKey = "hold:key:$holdKey"
            $exists = @(Invoke-RedisCommand -Arguments @('EXISTS', $metaKey))
            if ($exists.Count -ne 1 -or $exists[0] -notmatch '^\d+$') {
                throw "Unexpected EXISTS response for an exported hold key."
            }
            if ([int]$exists[0] -gt 0) {
                $keys.Add($metaKey)
            }
        }

        $uniqueKeys = @($keys | Sort-Object -Unique)
        foreach ($key in $uniqueKeys) {
            Assert-CoreCapacityRedisKey -Key $key -PerformanceId $PerformanceId -HoldKeys $holdKeys
        }
        return $uniqueKeys
    }

    try {
        $ping = @(Invoke-RedisCommand -Arguments @('PING'))
        if ($ping.Count -ne 1 -or $ping[0] -ne 'PONG') {
            throw 'Redis PING did not return PONG.'
        }

        Write-Host 'Core Capacity Redis reset'
        Write-Host "MODE            $Mode"
        Write-Host "TARGET          $RedisHost`:$RedisPort database=$RedisDatabase tls=$([bool]$Tls)"
        Write-Host "PERFORMANCE ID  $PerformanceId"
        Write-Host "EXPORTED HOLDS  $($holdKeys.Count)"

        $before = @(Get-TargetInventory)
        Write-Host "KEYS BEFORE     $($before.Count)"

        $deleted = 0
        if ($Mode -eq 'Delete') {
            for ($offset = 0; $offset -lt $before.Count; $offset += 100) {
                $last = [Math]::Min($offset + 99, $before.Count - 1)
                $batch = @($before[$offset..$last])
                foreach ($key in $batch) {
                    Assert-CoreCapacityRedisKey -Key $key -PerformanceId $PerformanceId -HoldKeys $holdKeys
                }
                $response = @(Invoke-RedisCommand -Arguments (@('UNLINK') + $batch))
                if ($response.Count -ne 1 -or $response[0] -notmatch '^\d+$') {
                    throw 'Unexpected Redis UNLINK response.'
                }
                $deleted += [int]$response[0]
            }
        }

        $after = @(Get-TargetInventory)
        Write-Host "KEYS DELETED    $deleted"
        Write-Host "KEYS AFTER      $($after.Count)"
        if ($Mode -eq 'Delete' -and $after.Count -ne 0) {
            throw "Target Redis keys remain after deletion: count=$($after.Count)"
        }

        $result = [ordered]@{
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            mode = $Mode.ToUpperInvariant()
            performanceId = $PerformanceId
            redisHost = $RedisHost
            redisPort = $RedisPort
            redisDatabase = $RedisDatabase
            tls = [bool]$Tls
            exportedHoldKeys = $holdKeys.Count
            keysBefore = $before.Count
            deletedKeys = $deleted
            keysAfter = $after.Count
            inspectionCompleted = $true
            clean = ($after.Count -eq 0)
            verified = ($after.Count -eq 0)
        }
        [IO.File]::WriteAllText(
            $resultPath,
            (($result | ConvertTo-Json) + "`n"),
            (New-Object Text.UTF8Encoding($false))
        )
        Write-Host "RESULT          $resultPath"
        return [pscustomobject]$result
    }
    finally {
        $plainPassword = $null
        if ($null -eq $previousAuth) {
            Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
        }
        else {
            $env:REDISCLI_AUTH = $previousAuth
        }
        $previousAuth = $null
    }
}


function Invoke-CoreCapacityResetPlan {
$interactiveConnectionSetup = [string]::IsNullOrWhiteSpace($OracleUser) -or
    [string]::IsNullOrWhiteSpace($OracleDataSource) -or
    [string]::IsNullOrWhiteSpace($RedisHost)
if ([string]::IsNullOrWhiteSpace($OracleUser)) {
    $OracleUser = Read-Host 'Oracle user'
}
if ([string]::IsNullOrWhiteSpace($OracleDataSource)) {
    $OracleDataSource = Read-Host 'Oracle data source (TNS alias or //host:port/service)'
}
if ([string]::IsNullOrWhiteSpace($RedisHost)) {
    $RedisHost = Read-Host 'Redis host'
}
if ($interactiveConnectionSetup) {
    $redisPortInput = Read-Host "Redis port [$RedisPort]"
    if (-not [string]::IsNullOrWhiteSpace($redisPortInput)) {
        $RedisPort = [int]$redisPortInput
    }
    $redisDatabaseInput = Read-Host "Redis database [$RedisDatabase]"
    if (-not [string]::IsNullOrWhiteSpace($redisDatabaseInput)) {
        $RedisDatabase = [int]$redisDatabaseInput
    }
    $tlsInput = Read-Host 'Redis TLS 사용 여부 [y/N]'
    if ($tlsInput -match '^(?i:y|yes)$') {
        $Tls = $true
    }
    $authenticationInput = Read-Host 'Redis 인증 방식 [password/no-auth] (기본 password)'
    if ($authenticationInput -match '^(?i:no-auth|none|n)$') {
        $RedisNoAuthentication = $true
    } else {
        $RedisUser = Read-Host 'Redis user (기본 ACL 사용자는 Enter)'
    }
}

if ($OracleUser -notmatch '^[A-Za-z0-9_$#.-]+$') {
    throw 'OracleUser contains an unsupported character.'
}
if ($OracleDataSource -notmatch '^[A-Za-z0-9._:/-]+$') {
    throw 'OracleDataSource must be a TNS alias or //host:port/service without spaces.'
}
if ($RedisHost -match '[\s"\r\n]') {
    throw 'RedisHost contains an unsupported whitespace, quote or newline.'
}
if ($RedisNoAuthentication -and ($null -ne $RedisPassword -or -not [string]::IsNullOrWhiteSpace($RedisUser))) {
    throw 'RedisNoAuthentication cannot be combined with RedisPassword or RedisUser.'
}

function Resolve-ExecutablePath {
    param([Parameter(Mandatory)][string]$PathOrName)

    if ([IO.File]::Exists($PathOrName)) {
        return [IO.Path]::GetFullPath($PathOrName)
    }
    $command = Get-Command $PathOrName -CommandType Application -ErrorAction Stop | Select-Object -First 1
    return $command.Source
}

function ConvertTo-RunnerPlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureValue)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function ConvertTo-NativeArgument {
    param([Parameter(Mandatory)][string]$Value)

    if ($Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw 'Native command argument contains an unsupported quote or newline.'
    }
    return '"' + $Value + '"'
}

function Invoke-OracleScript {
    param(
        [Parameter(Mandatory)][string]$ScriptPath,
        [Parameter(Mandatory)][string[]]$ScriptArguments,
        [Parameter(Mandatory)][string]$LogFile
    )

    $arguments = @('-L', '-S', "$OracleUser@$OracleDataSource", "@$ScriptPath") + $ScriptArguments
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $resolvedSqlClient
    $startInfo.Arguments = ($arguments | ForEach-Object { ConvertTo-NativeArgument -Value $_ }) -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $plainPassword = $null
    try {
        if (-not $process.Start()) {
            throw 'Failed to start the Oracle command-line client.'
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $plainPassword = ConvertTo-RunnerPlainText -SecureValue $OraclePassword
        $process.StandardInput.WriteLine($plainPassword)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $combined = $stdout
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            $combined += "`n[stderr]`n$stderr"
        }
        [IO.File]::WriteAllText($LogFile, $combined, (New-Object Text.UTF8Encoding($false)))
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.TrimEnd()
        }
        if ($process.ExitCode -ne 0) {
            throw "Oracle step failed with exit code $($process.ExitCode). See $LogFile"
        }
    }
    finally {
        $plainPassword = $null
        $process.Dispose()
    }
}

$resolvedSqlClient = Resolve-ExecutablePath -PathOrName $SqlClientPath
$resolvedRedisCli = Resolve-ExecutablePath -PathOrName $RedisCliPath

$precheckSqlContent = @'
-- Core Admission Capacity 회차 원복 전 읽기 전용 점검
-- 원복 실행 파일 내부 사전 점검 SQL

SET SERVEROUTPUT ON
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DEFINE performance_id = '&1'

VARIABLE target_performance_id NUMBER

BEGIN
    :target_performance_id := TO_NUMBER('&&performance_id');

    IF :target_performance_id NOT BETWEEN 910000001 AND 910000030 THEN
        RAISE_APPLICATION_ERROR(-20020, 'Core Capacity 전용 performanceId 범위가 아닙니다.');
    END IF;
END;
/

PROMPT
PROMPT === TARGET ===
SELECT
    p.id AS performance_id,
    p.performance_no,
    p.created_by,
    s.title AS show_title
FROM performances p
JOIN shows s ON s.id = p.show_id
WHERE p.id = :target_performance_id;

PROMPT
PROMPT === PERFORMANCE SEATS ===
SELECT
    COUNT(*) AS total_seats,
    SUM(CASE WHEN ps.state = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_db_seats,
    SUM(CASE WHEN ps.state <> 'AVAILABLE' THEN 1 ELSE 0 END) AS non_available_db_seats
FROM performance_seats ps
WHERE ps.performance_id = :target_performance_id;

PROMPT
PROMPT === ORDERS BY STATUS ===
SELECT o.status, COUNT(*) AS order_count
FROM orders o
WHERE o.performance_id = :target_performance_id
GROUP BY o.status
ORDER BY o.status;

PROMPT
PROMPT === RELATED ROWS ===
SELECT 'ORDERS' AS target, COUNT(*) AS row_count
FROM orders o
WHERE o.performance_id = :target_performance_id
UNION ALL
SELECT 'ORDER_SEATS', COUNT(*)
FROM order_seats os
WHERE os.order_id IN (
    SELECT o.id FROM orders o WHERE o.performance_id = :target_performance_id
)
UNION ALL
SELECT 'HOLD_HISTORY', COUNT(*)
FROM hold_history h
WHERE h.performance_id = :target_performance_id
UNION ALL
SELECT 'ORDER_HOLD_CREATION_OUTBOX', COUNT(*)
FROM order_hold_creation_outbox c
WHERE c.performance_id = :target_performance_id
UNION ALL
SELECT 'ORDER_HOLD_RELEASE_OUTBOX', COUNT(*)
FROM order_hold_release_outbox r
WHERE r.performance_id = :target_performance_id;

PROMPT
PROMPT === OUTBOX BY STATUS ===
SELECT 'CREATION' AS outbox_type, c.status, COUNT(*) AS row_count
FROM order_hold_creation_outbox c
WHERE c.performance_id = :target_performance_id
GROUP BY c.status
UNION ALL
SELECT 'RELEASE', r.status, COUNT(*)
FROM order_hold_release_outbox r
WHERE r.performance_id = :target_performance_id
GROUP BY r.status
ORDER BY outbox_type, status;

PROMPT
PROMPT === RESET BLOCKERS (all values must be 0) ===
SELECT 'NON_EXPIRED_ORDERS' AS blocker, COUNT(*) AS blocker_count
FROM orders o
WHERE o.performance_id = :target_performance_id
  AND o.status <> 'EXPIRED'
UNION ALL
SELECT 'CREATION_OUTBOX_NOT_COMPLETED', COUNT(*)
FROM order_hold_creation_outbox c
WHERE c.performance_id = :target_performance_id
  AND c.status <> 'COMPLETED'
UNION ALL
SELECT 'RELEASE_OUTBOX_NOT_COMPLETED', COUNT(*)
FROM order_hold_release_outbox r
WHERE r.performance_id = :target_performance_id
  AND (r.status <> 'COMPLETED' OR r.hold_released_at IS NULL)
UNION ALL
SELECT 'EXPIRED_ORDER_WITHOUT_COMPLETED_CREATION', COUNT(*)
FROM orders o
WHERE o.performance_id = :target_performance_id
  AND NOT EXISTS (
      SELECT 1
      FROM order_hold_creation_outbox c
      WHERE c.performance_id = o.performance_id
        AND c.hold_key = o.hold_key
        AND c.status = 'COMPLETED'
  )
UNION ALL
SELECT 'EXPIRED_ORDER_WITHOUT_COMPLETED_RELEASE', COUNT(*)
FROM orders o
WHERE o.performance_id = :target_performance_id
  AND NOT EXISTS (
      SELECT 1
      FROM order_hold_release_outbox r
      WHERE r.performance_id = o.performance_id
        AND r.hold_key = o.hold_key
        AND r.status = 'COMPLETED'
        AND r.hold_released_at IS NOT NULL
  )
UNION ALL
SELECT 'NON_AVAILABLE_DB_SEATS', COUNT(*)
FROM performance_seats ps
WHERE ps.performance_id = :target_performance_id
  AND ps.state <> 'AVAILABLE';

PROMPT
PROMPT 다음 단계로 진행하려면 전용 회차/좌석 2000개를 확인하고 RESET BLOCKERS가 모두 0이어야 합니다.

DECLARE
    v_count NUMBER;

    PROCEDURE assert_zero(p_count NUMBER, p_name VARCHAR2) IS
    BEGIN
        IF p_count <> 0 THEN
            RAISE_APPLICATION_ERROR(-20027, p_name || '가 0이 아닙니다. count=' || p_count);
        END IF;
    END;
BEGIN
    SELECT COUNT(*)
    INTO v_count
    FROM performances p
    JOIN shows s ON s.id = p.show_id
    WHERE p.id = :target_performance_id
      AND p.created_by = 'CORE_CAPACITY_20260812'
      AND s.title = '[LOAD TEST] Core Admission Capacity 2000석';

    IF v_count <> 1 THEN
        RAISE_APPLICATION_ERROR(-20021, '전용 Core Capacity performance가 아닙니다.');
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performance_seats ps
    WHERE ps.performance_id = :target_performance_id;

    IF v_count <> 2000 THEN
        RAISE_APPLICATION_ERROR(-20024, 'performance seat 수가 예상과 다릅니다. count=' || v_count);
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM orders o
    WHERE o.performance_id = :target_performance_id
      AND o.status <> 'EXPIRED';
    assert_zero(v_count, 'NON_EXPIRED_ORDERS');

    SELECT COUNT(*) INTO v_count
    FROM order_hold_creation_outbox c
    WHERE c.performance_id = :target_performance_id
      AND c.status <> 'COMPLETED';
    assert_zero(v_count, 'CREATION_OUTBOX_NOT_COMPLETED');

    SELECT COUNT(*) INTO v_count
    FROM order_hold_release_outbox r
    WHERE r.performance_id = :target_performance_id
      AND (r.status <> 'COMPLETED' OR r.hold_released_at IS NULL);
    assert_zero(v_count, 'RELEASE_OUTBOX_NOT_COMPLETED');

    SELECT COUNT(*) INTO v_count
    FROM orders o
    WHERE o.performance_id = :target_performance_id
      AND NOT EXISTS (
          SELECT 1 FROM order_hold_creation_outbox c
          WHERE c.performance_id = o.performance_id
            AND c.hold_key = o.hold_key
            AND c.status = 'COMPLETED'
      );
    assert_zero(v_count, 'ORDER_WITHOUT_COMPLETED_CREATION');

    SELECT COUNT(*) INTO v_count
    FROM orders o
    WHERE o.performance_id = :target_performance_id
      AND NOT EXISTS (
          SELECT 1 FROM order_hold_release_outbox r
          WHERE r.performance_id = o.performance_id
            AND r.hold_key = o.hold_key
            AND r.status = 'COMPLETED'
            AND r.hold_released_at IS NOT NULL
      );
    assert_zero(v_count, 'ORDER_WITHOUT_COMPLETED_RELEASE');

    SELECT COUNT(*) INTO v_count
    FROM performance_seats ps
    WHERE ps.performance_id = :target_performance_id
      AND ps.state <> 'AVAILABLE';
    assert_zero(v_count, 'NON_AVAILABLE_DB_SEATS');

    DBMS_OUTPUT.PUT_LINE('RESET PRECHECK PASSED');
END;
/
'@

$exportSqlContent = @'
-- Redis 원복에 사용할 대상 회차의 holdKey만 UTF-8 텍스트로 내보낸다.
-- 원복 실행 파일 내부 holdKey 내보내기 SQL

SET ECHO OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
SET TRIMSPOOL ON
SET TAB OFF
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DEFINE performance_id = '&1'
DEFINE output_file = '&2'

VARIABLE target_performance_id NUMBER

BEGIN
    :target_performance_id := TO_NUMBER('&&performance_id');

    IF :target_performance_id NOT BETWEEN 910000001 AND 910000030 THEN
        RAISE_APPLICATION_ERROR(-20020, 'Core Capacity 전용 performanceId 범위가 아닙니다.');
    END IF;

    DECLARE
        v_count NUMBER;
    BEGIN
        SELECT COUNT(*)
        INTO v_count
        FROM performances p
        WHERE p.id = :target_performance_id
          AND p.created_by = 'CORE_CAPACITY_20260812';

        IF v_count <> 1 THEN
            RAISE_APPLICATION_ERROR(-20021, '전용 Core Capacity performance가 아닙니다.');
        END IF;
    END;
END;
/

SPOOL "&&output_file"
SELECT hold_key
FROM (
    SELECT o.hold_key
    FROM orders o
    WHERE o.performance_id = :target_performance_id
    UNION
    SELECT h.hold_key
    FROM hold_history h
    WHERE h.performance_id = :target_performance_id
    UNION
    SELECT c.hold_key
    FROM order_hold_creation_outbox c
    WHERE c.performance_id = :target_performance_id
    UNION
    SELECT r.hold_key
    FROM order_hold_release_outbox r
    WHERE r.performance_id = :target_performance_id
)
ORDER BY hold_key;
SPOOL OFF
'@

$resetSqlContent = @'
-- Core Admission Capacity 전용 회차의 실행 이력 원복
--
-- 사전 조건
--   1. 대상 회차로 들어오는 부하가 완전히 중단되어 있어야 한다.
--   2. precheck의 RESET BLOCKERS가 모두 0이어야 한다.
--   3. holdKey를 내보낸 뒤 Redis inspect/delete/inspect를 완료해야 한다.
--
-- 실행:
-- Redis 검증 뒤 원복 실행 파일이 호출하는 Oracle 삭제 SQL

SET SERVEROUTPUT ON
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT 9 ROLLBACK

DEFINE performance_id = '&1'
DEFINE confirmation = '&2'

DECLARE
    c_created_by       CONSTANT VARCHAR2(255) := 'CORE_CAPACITY_20260812';
    c_seat_count       CONSTANT PLS_INTEGER := 2000;

    v_performance_id   NUMBER;
    v_confirmation     VARCHAR2(200);
    v_expected         VARCHAR2(200);
    v_count            NUMBER;
    v_order_seats      NUMBER;
    v_hold_history     NUMBER;
    v_creation_outbox  NUMBER;
    v_release_outbox   NUMBER;
    v_orders           NUMBER;

    PROCEDURE assert_zero(p_count NUMBER, p_name VARCHAR2) IS
    BEGIN
        IF p_count <> 0 THEN
            RAISE_APPLICATION_ERROR(-20022, p_name || '가 0이 아닙니다. count=' || p_count);
        END IF;
    END;
BEGIN
    v_performance_id := TO_NUMBER('&&performance_id');
    v_confirmation := '&&confirmation';
    v_expected := 'RESET_CORE_CAPACITY_' || v_performance_id || '_REDIS_VERIFIED';

    IF v_performance_id NOT BETWEEN 910000001 AND 910000030 THEN
        RAISE_APPLICATION_ERROR(-20020, 'Core Capacity 전용 performanceId 범위가 아닙니다.');
    END IF;

    IF v_confirmation <> v_expected THEN
        RAISE_APPLICATION_ERROR(-20023, '확인 문구가 일치하지 않습니다. expected=' || v_expected);
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performances p
    JOIN shows s ON s.id = p.show_id
    WHERE p.id = v_performance_id
      AND p.created_by = c_created_by
      AND s.title = '[LOAD TEST] Core Admission Capacity 2000석';

    IF v_count <> 1 THEN
        RAISE_APPLICATION_ERROR(-20021, '전용 Core Capacity performance가 아닙니다.');
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performance_seats ps
    WHERE ps.performance_id = v_performance_id;

    IF v_count <> c_seat_count THEN
        RAISE_APPLICATION_ERROR(-20024, 'performance seat 수가 예상과 다릅니다. count=' || v_count);
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performance_seats ps
    WHERE ps.performance_id = v_performance_id
      AND ps.state <> 'AVAILABLE';
    assert_zero(v_count, 'NON_AVAILABLE_DB_SEATS');

    SELECT COUNT(*)
    INTO v_count
    FROM orders o
    WHERE o.performance_id = v_performance_id
      AND o.status <> 'EXPIRED';
    assert_zero(v_count, 'NON_EXPIRED_ORDERS');

    SELECT COUNT(*)
    INTO v_count
    FROM order_hold_creation_outbox c
    WHERE c.performance_id = v_performance_id
      AND c.status <> 'COMPLETED';
    assert_zero(v_count, 'CREATION_OUTBOX_NOT_COMPLETED');

    SELECT COUNT(*)
    INTO v_count
    FROM order_hold_release_outbox r
    WHERE r.performance_id = v_performance_id
      AND (r.status <> 'COMPLETED' OR r.hold_released_at IS NULL);
    assert_zero(v_count, 'RELEASE_OUTBOX_NOT_COMPLETED');

    SELECT COUNT(*)
    INTO v_count
    FROM orders o
    WHERE o.performance_id = v_performance_id
      AND NOT EXISTS (
          SELECT 1
          FROM order_hold_creation_outbox c
          WHERE c.performance_id = o.performance_id
            AND c.hold_key = o.hold_key
            AND c.status = 'COMPLETED'
      );
    assert_zero(v_count, 'ORDER_WITHOUT_COMPLETED_CREATION');

    SELECT COUNT(*)
    INTO v_count
    FROM orders o
    WHERE o.performance_id = v_performance_id
      AND NOT EXISTS (
          SELECT 1
          FROM order_hold_release_outbox r
          WHERE r.performance_id = o.performance_id
            AND r.hold_key = o.hold_key
            AND r.status = 'COMPLETED'
            AND r.hold_released_at IS NOT NULL
      );
    assert_zero(v_count, 'ORDER_WITHOUT_COMPLETED_RELEASE');

    DELETE FROM order_seats os
    WHERE os.order_id IN (
        SELECT o.id
        FROM orders o
        WHERE o.performance_id = v_performance_id
    );
    v_order_seats := SQL%ROWCOUNT;

    DELETE FROM hold_history h
    WHERE h.performance_id = v_performance_id;
    v_hold_history := SQL%ROWCOUNT;

    DELETE FROM order_hold_release_outbox r
    WHERE r.performance_id = v_performance_id;
    v_release_outbox := SQL%ROWCOUNT;

    DELETE FROM order_hold_creation_outbox c
    WHERE c.performance_id = v_performance_id;
    v_creation_outbox := SQL%ROWCOUNT;

    DELETE FROM orders o
    WHERE o.performance_id = v_performance_id;
    v_orders := SQL%ROWCOUNT;

    SELECT
        (SELECT COUNT(*) FROM orders o WHERE o.performance_id = v_performance_id)
      + (SELECT COUNT(*)
         FROM order_seats os
         JOIN performance_seats ps ON ps.id = os.performance_seat_id
         WHERE ps.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM hold_history h WHERE h.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM order_hold_creation_outbox c WHERE c.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM order_hold_release_outbox r WHERE r.performance_id = v_performance_id)
    INTO v_count
    FROM dual;
    assert_zero(v_count, 'REMAINING_RESET_ROWS');

    COMMIT;

    DBMS_OUTPUT.PUT_LINE('Core capacity run reset committed.');
    DBMS_OUTPUT.PUT_LINE('performanceId=' || v_performance_id);
    DBMS_OUTPUT.PUT_LINE('deleted ORDER_SEATS=' || v_order_seats);
    DBMS_OUTPUT.PUT_LINE('deleted HOLD_HISTORY=' || v_hold_history);
    DBMS_OUTPUT.PUT_LINE('deleted ORDER_HOLD_RELEASE_OUTBOX=' || v_release_outbox);
    DBMS_OUTPUT.PUT_LINE('deleted ORDER_HOLD_CREATION_OUTBOX=' || v_creation_outbox);
    DBMS_OUTPUT.PUT_LINE('deleted ORDERS=' || v_orders);
    DBMS_OUTPUT.PUT_LINE('PERFORMANCE_SEATS was not updated.');
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/
'@

$postcheckSqlContent = @'
-- Core Admission Capacity 회차 원복 후 읽기 전용 검증
-- 원복 실행 파일 내부 사후 검증 SQL

SET SERVEROUTPUT ON
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DEFINE performance_id = '&1'

DECLARE
    v_performance_id NUMBER;
    v_count NUMBER;
BEGIN
    v_performance_id := TO_NUMBER('&&performance_id');

    IF v_performance_id NOT BETWEEN 910000001 AND 910000030 THEN
        RAISE_APPLICATION_ERROR(-20020, 'Core Capacity 전용 performanceId 범위가 아닙니다.');
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performances p
    JOIN shows s ON s.id = p.show_id
    WHERE p.id = v_performance_id
      AND p.created_by = 'CORE_CAPACITY_20260812'
      AND s.title = '[LOAD TEST] Core Admission Capacity 2000석';

    IF v_count <> 1 THEN
        RAISE_APPLICATION_ERROR(-20021, '전용 Core Capacity performance가 아닙니다.');
    END IF;

    SELECT
        (SELECT COUNT(*) FROM orders o WHERE o.performance_id = v_performance_id)
      + (SELECT COUNT(*)
         FROM order_seats os
         JOIN performance_seats ps ON ps.id = os.performance_seat_id
         WHERE ps.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM hold_history h WHERE h.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM order_hold_creation_outbox c WHERE c.performance_id = v_performance_id)
      + (SELECT COUNT(*) FROM order_hold_release_outbox r WHERE r.performance_id = v_performance_id)
    INTO v_count
    FROM dual;

    IF v_count <> 0 THEN
        RAISE_APPLICATION_ERROR(-20025, '원복 대상 실행 이력이 남아 있습니다. count=' || v_count);
    END IF;

    SELECT COUNT(*)
    INTO v_count
    FROM performance_seats ps
    WHERE ps.performance_id = v_performance_id
      AND ps.state = 'AVAILABLE';

    IF v_count <> 2000 THEN
        RAISE_APPLICATION_ERROR(-20026, 'AVAILABLE performance seat 수가 2000이 아닙니다. count=' || v_count);
    END IF;

    DBMS_OUTPUT.PUT_LINE('RESET VERIFIED');
    DBMS_OUTPUT.PUT_LINE('performanceId=' || v_performance_id);
    DBMS_OUTPUT.PUT_LINE('related run rows=0');
    DBMS_OUTPUT.PUT_LINE('AVAILABLE performance seats=2000');
END;
/
'@

$performanceIds = @(910000001L..910000030L)
$timestamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "all-performances-$timestamp"

Write-Host ''
Write-Host 'CORE CAPACITY ALL-PERFORMANCE RESET'
Write-Host 'PERFORMANCE IDS 910000001..910000030 (30 performances)'
Write-Host "ORACLE          $OracleUser@$OracleDataSource"
Write-Host "REDIS           $RedisHost`:$RedisPort database=$RedisDatabase tls=$([bool]$Tls)"
Write-Host "OUTPUT          $runDirectory"
Write-Host ''

if (-not $OperationalConfirmation) {
    $expectedOperationalConfirmation = 'OPERATE_CORE_CAPACITY_RESET_ALL_30_PERFORMANCES'
    Write-Host 'To connect to the target above, enter the following phrase.'
    Write-Host $expectedOperationalConfirmation
    $actualOperationalConfirmation = Read-Host 'Confirmation'
    if ($actualOperationalConfirmation -cne $expectedOperationalConfirmation) {
        throw "Operational confirmation must exactly equal: $expectedOperationalConfirmation"
    }
}

[IO.Directory]::CreateDirectory($runDirectory) | Out-Null
$sqlDirectory = Join-Path $runDirectory 'internal-sql'
[IO.Directory]::CreateDirectory($sqlDirectory) | Out-Null
$precheckSql = Join-Path $sqlDirectory 'precheck.sql'
$exportSql = Join-Path $sqlDirectory 'export-hold-keys.sql'
$resetSql = Join-Path $sqlDirectory 'reset-run.sql'
$postcheckSql = Join-Path $sqlDirectory 'postcheck.sql'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText($precheckSql, $precheckSqlContent, $utf8NoBom)
[IO.File]::WriteAllText($exportSql, $exportSqlContent, $utf8NoBom)
[IO.File]::WriteAllText($resetSql, $resetSqlContent, $utf8NoBom)
[IO.File]::WriteAllText($postcheckSql, $postcheckSqlContent, $utf8NoBom)
if ($null -eq $OraclePassword) {
    $OraclePassword = Read-Host 'Oracle password' -AsSecureString
}
if (-not $RedisNoAuthentication -and $null -eq $RedisPassword) {
    $RedisPassword = Read-Host 'Redis password (use -RedisNoAuthentication when authentication is disabled)' -AsSecureString
}

Write-Host '[1/8] Oracle precheck for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    Invoke-OracleScript -ScriptPath $precheckSql -ScriptArguments @([string]$targetPerformanceId) `
        -LogFile (Join-Path $runDirectory "$targetPerformanceId-01-precheck-before-stop.log")
}

$expectedCoreConfirmation = 'RESULTS_SAVED_AND_CORE_STOPPED_ALL_30_PERFORMANCES'
if ([string]::IsNullOrWhiteSpace($CoreStoppedConfirmation)) {
    Write-Host ''
    Write-Host 'Save every load-test result and stop every Core instance, then enter the following phrase.'
    Write-Host $expectedCoreConfirmation
    $CoreStoppedConfirmation = Read-Host 'Confirmation'
}
if ($CoreStoppedConfirmation -cne $expectedCoreConfirmation) {
    throw "CoreStoppedConfirmation must exactly equal: $expectedCoreConfirmation"
}

Write-Host '[2/8] Oracle precheck after Core stop for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    Invoke-OracleScript -ScriptPath $precheckSql -ScriptArguments @([string]$targetPerformanceId) `
        -LogFile (Join-Path $runDirectory "$targetPerformanceId-02-precheck-after-stop.log")
}

Write-Host '[3/8] Export hold keys for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    $holdKeysFile = Join-Path $runDirectory "$targetPerformanceId-hold-keys.txt"
    Invoke-OracleScript -ScriptPath $exportSql -ScriptArguments @([string]$targetPerformanceId, $holdKeysFile) `
        -LogFile (Join-Path $runDirectory "$targetPerformanceId-03-export-hold-keys.log")
}

Write-Host '[4/8] Redis inspect for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    $redisCommon = @{
        PerformanceId = $targetPerformanceId
        HoldKeysFile = (Join-Path $runDirectory "$targetPerformanceId-hold-keys.txt")
        RedisHost = $RedisHost
        RedisPort = $RedisPort
        RedisDatabase = $RedisDatabase
        RedisCliPath = $resolvedRedisCli
        OperationalConfirmation = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($RedisUser)) { $redisCommon['RedisUser'] = $RedisUser }
    if ($null -ne $RedisPassword) { $redisCommon['RedisPassword'] = $RedisPassword }
    if ($Tls) { $redisCommon['Tls'] = $true }
    Invoke-CoreCapacityRedisReset @redisCommon -Mode Inspect `
        -ResultFile (Join-Path $runDirectory "$targetPerformanceId-04-redis-before.json") | Out-Null
}

$expectedDeleteConfirmation = 'DELETE_ALL_CORE_CAPACITY_30_PERFORMANCES'
if ([string]::IsNullOrWhiteSpace($DeleteConfirmation)) {
    Write-Host ''
    Write-Host 'All 30 Oracle and Redis targets passed dry-run inspection. Enter the following phrase to reset all of them.'
    Write-Host $expectedDeleteConfirmation
    $DeleteConfirmation = Read-Host 'Confirmation'
}
if ($DeleteConfirmation -cne $expectedDeleteConfirmation) {
    throw "DeleteConfirmation must exactly equal: $expectedDeleteConfirmation"
}

Write-Host '[5/8] Redis delete and re-inspect for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    $redisCommon = @{
        PerformanceId = $targetPerformanceId
        HoldKeysFile = (Join-Path $runDirectory "$targetPerformanceId-hold-keys.txt")
        RedisHost = $RedisHost
        RedisPort = $RedisPort
        RedisDatabase = $RedisDatabase
        RedisCliPath = $resolvedRedisCli
        OperationalConfirmation = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($RedisUser)) { $redisCommon['RedisUser'] = $RedisUser }
    if ($null -ne $RedisPassword) { $redisCommon['RedisPassword'] = $RedisPassword }
    if ($Tls) { $redisCommon['Tls'] = $true }
    Invoke-CoreCapacityRedisReset @redisCommon -Mode Delete `
        -DeleteConfirmation "DELETE_CORE_CAPACITY_${targetPerformanceId}_REDIS_KEYS" `
        -ResultFile (Join-Path $runDirectory "$targetPerformanceId-05-redis-delete.json") | Out-Null
}

Write-Host '[6/8] Oracle run-history delete for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    $dbConfirmation = "RESET_CORE_CAPACITY_${targetPerformanceId}_REDIS_VERIFIED"
    Invoke-OracleScript -ScriptPath $resetSql -ScriptArguments @([string]$targetPerformanceId, $dbConfirmation) `
        -LogFile (Join-Path $runDirectory "$targetPerformanceId-06-oracle-reset.log")
}

Write-Host '[7/8] Oracle postcheck for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    Invoke-OracleScript -ScriptPath $postcheckSql -ScriptArguments @([string]$targetPerformanceId) `
        -LogFile (Join-Path $runDirectory "$targetPerformanceId-07-oracle-postcheck.log")
}

Write-Host '[8/8] Redis final inspect for all performances'
foreach ($targetPerformanceId in $performanceIds) {
    $redisCommon = @{
        PerformanceId = $targetPerformanceId
        HoldKeysFile = (Join-Path $runDirectory "$targetPerformanceId-hold-keys.txt")
        RedisHost = $RedisHost
        RedisPort = $RedisPort
        RedisDatabase = $RedisDatabase
        RedisCliPath = $resolvedRedisCli
        OperationalConfirmation = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($RedisUser)) { $redisCommon['RedisUser'] = $RedisUser }
    if ($null -ne $RedisPassword) { $redisCommon['RedisPassword'] = $RedisPassword }
    if ($Tls) { $redisCommon['Tls'] = $true }
    $redisAfter = Invoke-CoreCapacityRedisReset @redisCommon -Mode Inspect `
        -ResultFile (Join-Path $runDirectory "$targetPerformanceId-08-redis-after.json")
    if (-not $redisAfter.clean) {
        throw "Redis final inspection is not clean for performance $targetPerformanceId. Keep Core stopped."
    }
}

$summary = [ordered]@{
    completedAt = [DateTimeOffset]::Now.ToString('o')
    performanceIds = $performanceIds
    performanceCount = $performanceIds.Count
    dbRunRows = 0
    redisKeys = 0
}
[IO.File]::WriteAllText(
    (Join-Path $runDirectory 'reset-all-summary.json'),
    ($summary | ConvertTo-Json -Depth 4),
    $utf8NoBom
)

Write-Host ''
Write-Host 'ALL-PERFORMANCE RESET COMPLETED'
Write-Host 'PERFORMANCE IDS 910000001..910000030'
Write-Host 'DB RUN ROWS      0 (all postchecks passed)'
Write-Host 'REDIS KEYS       0 (all final inspections passed)'
Write-Host "EVIDENCE         $runDirectory"
Write-Host 'Restart Core, then verify the next target performance returns 2,000 AVAILABLE seats.'
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-CoreCapacityResetPlan
}
