# Load test in an ISOLATED copy of the system (its own containers and data), so your normal data is never touched.
# Runs one k6 test per load level, samples CPU, writes a results table, then checks that the data is fully consistent.
#   powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1 [-Levels "10 25 50 100 200"] [-Duration 45s] [-Users 200]
param(
    # a string, split below: with "-File", "10,50" would otherwise be read as the number 1050 (thousands separator)
    [string]$Levels = "10 25 50 100 200",
    [string]$Duration = "45s",
    [int]$Users = 200
)
$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$results = Join-Path $root "load-test\results"
Set-Location $root
$project = "dwload"
$levelList = $Levels -split "[\s;,]+" | Where-Object { $_ } | ForEach-Object { [int]$_ }

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
# Docker writes progress to stderr; running it through cmd keeps Windows PowerShell from treating that as an error
function Run([string]$command) {
    cmd /c "$command 2>&1" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $command" }
}
function Sql([string]$query, [switch]$Scalar) {
    $options = if ($Scalar) { "-N -s" } else { "--table" }
    $output = $query | cmd /c "docker compose -p $project exec -T mysql mysql -udigitalwallet -pdigitalwallet $options 2>&1"
    $output | Where-Object { $_ -notmatch "Using a password" }
}
function Ms($seconds) { if ($null -eq $seconds) { "-" } else { "{0:N0}" -f $seconds } }

# the normal stack uses the same ports, so it is paused (its data is kept) and restarted at the end
$mainWasRunning = [bool](docker compose --profile app ps -q 2>$null)
if ($mainWasRunning) {
    Step "Pausing your normal stack (data is kept)"
    Run "docker compose --profile app down"
}

try {
    Step "Building and starting an isolated load-test stack"
    $env:TRANSFERS_PER_MINUTE = "1000000"   # the per-user rate limit would otherwise cap the test
    Run "docker compose -p $project --profile app up -d --build"
    $deadline = (Get-Date).AddMinutes(5)
    foreach ($port in 8081, 8082) {
        while ($true) {
            try { if ((Invoke-RestMethod "http://localhost:$port/actuator/health/readiness" -TimeoutSec 2).status -eq "UP") { break } }
            catch { }
            if ((Get-Date) -gt $deadline) { throw "service on port $port did not become ready" }
            Start-Sleep -Seconds 3
        }
    }

    New-Item -ItemType Directory -Force $results | Out-Null
    Remove-Item "$results\summary-*.json" -ErrorAction SilentlyContinue
    $rows = @()
    $allPassed = $true
    foreach ($vus in $levelList) {
        Step "Load level: $vus concurrent users for $Duration"
        $k6 = Start-Job -ArgumentList $project, $results, $Users, $vus, $Duration -ScriptBlock {
            param($project, $results, $users, $vus, $duration)
            $out = cmd /c "docker run --rm --name dwload-k6 --network ${project}_default -v ""$results\..:/scripts"" grafana/k6:1.8.1 run --quiet -e USERS=$users -e VUS=$vus -e DURATION=$duration --summary-export /scripts/results/summary-$vus.json /scripts/transfers.js 2>&1"
            [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $out }
        }
        # sample CPU while the test runs (100% = one full CPU core)
        $peak = @{}
        while ($k6.State -eq "Running") {
            cmd /c "docker stats --no-stream --format ""{{.Name}};{{.CPUPerc}}"" 2>&1" | ForEach-Object {
                $parts = "$_" -split ";"
                if ($parts.Count -eq 2 -and $parts[1] -match "([\d.]+)%") {
                    $name = $parts[0] -replace "^dwload-", "" -replace "-1$", ""
                    $cpu = [double]$Matches[1]
                    if (-not $peak.ContainsKey($name) -or $cpu -gt $peak[$name]) { $peak[$name] = $cpu }
                }
            }
            Start-Sleep -Seconds 2
        }
        $k6Result = Receive-Job $k6 -Wait -AutoRemoveJob
        $k6Result.Output | Where-Object { "$_" -match "level=error|crossed" } | Select-Object -First 6 | Write-Host
        if ($k6Result.Exit -ne 0) { $allPassed = $false }

        $m = (Get-Content "$results\summary-$vus.json" -Raw | ConvertFrom-Json).metrics
        $t = $m.'http_req_duration{name:transfer}'
        $r = $m.'http_req_duration{name:read}'
        $row = [pscustomobject]@{
            'Users'             = $vus
            'Req/s'             = "{0:N1}" -f $m.http_reqs.rate
            'Transfers/s'       = "{0:N1}" -f $m.transfers_ok.rate
            'Transfer p50 ms'   = Ms $t.med
            'Transfer p95 ms'   = Ms $t.'p(95)'
            'Transfer p99 ms'   = Ms $t.'p(99)'
            'Read p95 ms'       = Ms $r.'p(95)'
            'Busy retries'      = [int]$m.busy_retries.count
            'Shed'              = [int]$m.requests_shed.count
            'Errors'            = [int]$m.unexpected_errors.count
            'Double charges'    = [int]$m.double_charges.count
            'Peak CPU wallet %' = "{0:N0}" -f $peak['wallet-service']
            'Peak CPU mysql %'  = "{0:N0}" -f $peak['mysql']
            'Peak CPU k6 %'     = "{0:N0}" -f $peak['k6']
        }
        $row | Format-List | Out-String | Write-Host
        $rows += $row
    }

    docker compose -p $project logs --no-color wallet-service rewards-service 2>&1 | ForEach-Object { "$_" } |
        Out-File -Encoding utf8 (Join-Path $results "service-logs.txt")

    Step "Waiting for every event to flow through Kafka (outbox drained, consumer lag 0)"
    $deadline = (Get-Date).AddMinutes(3)
    while ($true) {
        $pending = [int](Sql -Scalar "SELECT (SELECT COUNT(*) FROM wallet_db.outbox_events WHERE status <> 'PUBLISHED') + (SELECT COUNT(*) FROM rewards_db.outbox_events WHERE status <> 'PUBLISHED');")
        $lag = 0
        cmd /c "docker compose -p $project exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups 2>&1" |
            ForEach-Object {
                $cols = ("$_" -split "\s+") | Where-Object { $_ }
                if ($cols.Count -ge 6 -and $cols[5] -match "^\d+$") { $lag += [int]$cols[5] }
            }
        Write-Host "   pending outbox events: $pending, Kafka consumer lag: $lag"
        if ($pending -eq 0 -and $lag -eq 0) { break }
        if ((Get-Date) -gt $deadline) { Write-Warning "events still in flight after 3 minutes"; break }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 3

    Step "Data consistency checks over everything the load test did (every 'problems' value must be 0)"
    $checkSql = Get-Content (Join-Path $root "load-test\verify-consistency.sql") -Raw
    $checks = Sql $checkSql
    $checks | Write-Host
    $problemSql = "SELECT SUM(problems) FROM (" + ($checkSql -split "-- Volume")[0].Trim().TrimEnd(';') + ") t;"
    $problems = [int](Sql -Scalar $problemSql)

    $names = @($rows[0].PSObject.Properties | ForEach-Object { $_.Name })
    $header = "| " + [string]::Join(" | ", $names) + " |"
    $divider = "|" + [string]::Join("|", @($names | ForEach-Object { "---" })) + "|"
    $tableRows = @($rows | ForEach-Object { "| " + [string]::Join(" | ", @($_.PSObject.Properties | ForEach-Object { "$($_.Value)" })) + " |" })
    $report = @(
        "# Load test results ($(Get-Date -Format 'yyyy-MM-dd HH:mm'))",
        "",
        "Each level ran for $Duration. Every virtual user pauses 0.5-1.5 s between actions.",
        "Mix: 60% transfers (15% of them to 3 popular merchants), 30% balance/statement reads, 10% top-ups.",
        "",
        $header,
        $divider
    ) + $tableRows + @("", "## Data consistency after all levels", "", '```') + @($checks) + @('```')
    $report | Out-File -Encoding utf8 (Join-Path $results "report.md")

    Step "Summary"
    $rows | Format-Table -AutoSize | Out-String -Width 260 | Write-Host
    if ($allPassed -and $problems -eq 0) {
        Write-Host "LOAD TEST PASSED: no errors, no double charges, data fully consistent. Report: load-test\results\report.md" -ForegroundColor Green
    } else {
        Write-Host "LOAD TEST FAILED (thresholds passed: $allPassed, consistency problems: $problems)" -ForegroundColor Red
        $global:LASTEXITCODE = 1
    }
}
finally {
    Step "Removing the isolated load-test stack and its data"
    try { Run "docker compose -p $project --profile app down -v" } catch { Write-Warning $_ }
    Remove-Item Env:TRANSFERS_PER_MINUTE -ErrorAction SilentlyContinue
    if ($mainWasRunning) {
        Step "Restarting your normal stack"
        try { Run "docker compose --profile app up -d" } catch { Write-Warning $_ }
    }
}
