$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$dataDirectory = Join-Path $repositoryRoot 'scripts\core-capacity'
$createSqlPath = Join-Path $dataDirectory 'create-core-capacity-data.sql'
$resetScriptPath = Join-Path $repositoryRoot 'scripts\core-capacity\reset-core-capacity.ps1'

$createSql = Get-Content -Raw -Encoding UTF8 $createSqlPath
$resetScript = Get-Content -Raw -Encoding UTF8 $resetScriptPath

. $resetScriptPath -OperationalConfirmation

Describe 'Core Capacity compact operator files' {
    It 'keeps every operator-facing file in one directory' {
        @(Get-ChildItem $dataDirectory -File | Sort-Object Name).Name | Should Be @(
            'create-core-capacity-data.sql',
            'README.md',
            'reset-core-capacity.ps1'
        )
    }

    It 'parses the reset PowerShell operator script' {
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile($resetScriptPath, [ref]$tokens, [ref]$errors) | Out-Null
        @($errors).Count | Should Be 0
    }
}

Describe 'Core Capacity Oracle creation contract' {
    It 'creates every required table without destructive statements' {
        foreach ($table in @(
            'venues',
            'shows',
            'seats',
            'show_grades',
            'show_seats',
            'members',
            'performances',
            'performance_queue_policies',
            'performance_seats'
        )) {
            $createSql | Should Match ("(?i)INSERT\s+INTO\s+" + $table + "\b")
        }
        $createSql | Should Not Match '(?im)^\s*(DELETE|UPDATE|MERGE|TRUNCATE|DROP)\b'
    }

    It 'creates 30 independent 2000-seat performances with Queue bypassed' {
        $createSql | Should Match 'c_seat_count\s+CONSTANT\s+PLS_INTEGER\s*:=\s*2000'
        $createSql | Should Match 'c_performance_count\s+CONSTANT\s+PLS_INTEGER\s*:=\s*30'
        $createSql | Should Match "'FORCE_OFF'"
        $createSql | Should Match "'AVAILABLE'"
        $createSql | Should Match 'c_member_count\s+CONSTANT\s+PLS_INTEGER\s*:=\s*c_seat_count'
        $createSql | Should Match 'c_member_id_base\s+CONSTANT\s+NUMBER\s*:=\s*0'
        $createSql | Should Match '(?im)^\s*COMMIT;'
        $createSql | Should Match '(?im)^\s*\s*ROLLBACK;'
    }
}

Describe 'Core Capacity reset contract' {
    It 'accepts only the dedicated performance range' {
        { Assert-CoreCapacityPerformanceId -PerformanceId 910000001 } | Should Not Throw
        { Assert-CoreCapacityPerformanceId -PerformanceId 910000030 } | Should Not Throw
        { Assert-CoreCapacityPerformanceId -PerformanceId 910000000 } | Should Throw
        { Assert-CoreCapacityPerformanceId -PerformanceId 910000031 } | Should Throw
    }

    It 'embeds the full Oracle and Redis safety sequence without external SQL dependencies' {
        foreach ($step in @(
            'Oracle precheck',
            'Oracle precheck after Core stop',
            'Export hold keys',
            'Redis inspect',
            'Redis delete and re-inspect',
            'Oracle run-history delete',
            'Oracle postcheck',
            'Redis final inspect'
        )) {
            $resetScript | Should Match ([regex]::Escape($step))
        }
        $resetScript | Should Match 'DELETE FROM order_seats'
        $resetScript | Should Match 'DELETE FROM orders'
        $resetScript | Should Match 'PERFORMANCE_SEATS was not updated'
        $resetScript | Should Match "'--scan'"
        $resetScript | Should Match "'UNLINK'"
        $resetScript | Should Not Match 'docs\\core-capacity-data|Import-Module'
    }

    It 'requires expired orders, completed outboxes and available DB seats' {
        $resetScript | Should Match "o\.status\s*<>\s*'EXPIRED'"
        $resetScript | Should Match "c\.status\s*<>\s*'COMPLETED'"
        $resetScript | Should Match "r\.status\s*<>\s*'COMPLETED'"
        $resetScript | Should Match 'r\.hold_released_at\s+IS\s+NULL'
        $resetScript | Should Match "ps\.state\s*<>\s*'AVAILABLE'"
    }

    It 'allows only exact performance keys and exported hold metadata' {
        $holdKey = 'HOLD-0123456789abcdef0123456789abcdef'
        $allowed = @($holdKey)

        foreach ($key in @(
            'seat:select:index:{perf:910000001}',
            'seat:hold:index:{perf:910000001}',
            'seat:select:{perf:910000001}:910000001',
            'seat:hold:{perf:910000001}:910000002',
            "hold:key:$holdKey"
        )) {
            { Assert-CoreCapacityRedisKey -Key $key -PerformanceId 910000001 -HoldKeys $allowed } | Should Not Throw
        }

        { Assert-CoreCapacityRedisKey -Key 'seat:hold:{perf:910000002}:910000001' `
            -PerformanceId 910000001 -HoldKeys $allowed } | Should Throw
        { Assert-CoreCapacityRedisKey -Key 'some-unrelated-key' `
            -PerformanceId 910000001 -HoldKeys $allowed } | Should Throw
    }

    It 'resets every dedicated performance only after one all-target dry run and confirmation' {
        $resetScript | Should Match '\$performanceIds\s*=\s*@\(910000001L\.\.910000030L\)'
        $resetScript | Should Match 'OPERATE_CORE_CAPACITY_RESET_ALL_30_PERFORMANCES'
        $resetScript | Should Match 'RESULTS_SAVED_AND_CORE_STOPPED_ALL_30_PERFORMANCES'
        $resetScript | Should Match 'DELETE_ALL_CORE_CAPACITY_30_PERFORMANCES'
        $resetScript | Should Match '\$targetPerformanceId-04-redis-before\.json'
        $resetScript | Should Match '\$targetPerformanceId-08-redis-after\.json'
        $resetScript | Should Match 'reset-all-summary\.json'
        $resetScript | Should Not Match '(?m)^\s*\[long\]\$PerformanceId,'
    }
}
