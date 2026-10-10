# End-to-end demo of Digital Wallet. Requires both services running on 8081 / 8082.
#   powershell -ExecutionPolicy Bypass -File scripts\demo.ps1
$ErrorActionPreference = "Stop"
$wallet  = "http://localhost:8081"
$rewards = "http://localhost:8082"
$run = Get-Random -Maximum 1000000

function Post($url, $body, $token, $key) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    if ($key)   { $headers["Idempotency-Key"] = $key }
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType "application/json" -Body ($body | ConvertTo-Json)
}
function Get-Json($url, $token) {
    Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Bearer $token" }
}
function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

Step "Register Alice and Bob (a wallet is opened for each)"
$alice = Post "$wallet/api/auth/register" @{ email = "alice$run@demo.dev"; password = "Secret123"; fullName = "Alice" }
$bob   = Post "$wallet/api/auth/register" @{ email = "bob$run@demo.dev";   password = "Secret123"; fullName = "Bob" }
"Alice userId=$($alice.userId), Bob userId=$($bob.userId)"

Step "Alice tops up 5000 (earns 1% top-up bonus, capped at 50)"
$topUpKey = [guid]::NewGuid().ToString()
$topUp = Post "$wallet/api/wallets/me/top-ups" @{ amount = 5000 } $alice.accessToken $topUpKey
"Transaction $($topUp.transactionId)"

Step "Client retries the same top-up with the same Idempotency-Key (must NOT charge twice)"
$retry = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$wallet/api/wallets/me/top-ups" `
    -Headers @{ Authorization = "Bearer $($alice.accessToken)"; "Idempotency-Key" = $topUpKey } `
    -ContentType "application/json" -Body (@{ amount = 5000 } | ConvertTo-Json)
"HTTP $($retry.StatusCode), Idempotent-Replayed=$($retry.Headers['Idempotent-Replayed'])"

Step "Alice sends 1000 to Bob (earns 2% transfer cashback = 20)"
$transfer = Post "$wallet/api/wallets/me/transfers" @{ recipientEmail = "bob$run@demo.dev"; amount = 1000; note = "Dinner" } `
    $alice.accessToken ([guid]::NewGuid().ToString())
"Transaction $($transfer.transactionId)"

Step "Alice tries to send 99999 (more than she has)"
try {
    Post "$wallet/api/wallets/me/transfers" @{ recipientEmail = "bob$run@demo.dev"; amount = 99999 } `
        $alice.accessToken ([guid]::NewGuid().ToString()) | Out-Null
} catch {
    $response = $_.Exception.Response
    $body = (New-Object System.IO.StreamReader($response.GetResponseStream())).ReadToEnd()
    "Rejected with HTTP $([int]$response.StatusCode): $body"
}

Step "Waiting for cashback to flow wallet -> Kafka -> rewards -> Kafka -> wallet"
$expected = [decimal]4070.00   # 5000 - 1000 + 50 bonus + 20 cashback
for ($i = 0; $i -lt 30; $i++) {
    $balance = (Get-Json "$wallet/api/wallets/me" $alice.accessToken).balance
    if ([decimal]$balance -eq $expected) { break }
    Start-Sleep -Seconds 1
}
"Alice balance = $balance (expected $expected)"
"Bob balance   = $((Get-Json "$wallet/api/wallets/me" $bob.accessToken).balance)"
$cashbackArrived = [decimal]$balance -eq $expected

Step "QR payment: Bob creates a single-use QR code for 250 (signed, expires in 15 min)"
$qr = Post "$wallet/api/qr" @{ amount = 250 } $bob.accessToken
$qrFile = Join-Path $PSScriptRoot "qr-demo.png"
[IO.File]::WriteAllBytes($qrFile, [Convert]::FromBase64String($qr.pngBase64))
"QR image saved to $qrFile (open it: it is a real, scannable QR code)"
"Payload: $($qr.payload.Substring(0, 40))..."

Step "Alice scans it: the app shows who will be paid"
Post "$wallet/api/qr/preview" @{ payload = $qr.payload } $alice.accessToken |
    Format-List payeeName, payeeEmail, amount, singleUse, expiresAt, alreadyPaid | Out-String

Step "Alice pays the QR code"
$qrPayment = Post "$wallet/api/qr/pay" @{ payload = $qr.payload } $alice.accessToken ([guid]::NewGuid().ToString())
"Paid: transaction $($qrPayment.transactionId), $($qrPayment.description)"

Step "Someone tries to pay the same single-use QR again"
try {
    Post "$wallet/api/qr/pay" @{ payload = $qr.payload } $alice.accessToken ([guid]::NewGuid().ToString()) | Out-Null
    "Paid twice (unexpected)"
} catch {
    $response = $_.Exception.Response
    $body = (New-Object System.IO.StreamReader($response.GetResponseStream())).ReadToEnd()
    "Rejected with HTTP $([int]$response.StatusCode): $(($body | ConvertFrom-Json).code)"
}
$expected = $expected - 250    # 250 is below the 500 cashback threshold, so no reward
$balance = (Get-Json "$wallet/api/wallets/me" $alice.accessToken).balance
"Alice balance = $balance (expected $expected)"
"Bob balance   = $((Get-Json "$wallet/api/wallets/me" $bob.accessToken).balance)"

Step "Alice's statement (double-entry ledger)"
(Get-Json "$wallet/api/wallets/me/statement" $alice.accessToken).content |
    Format-Table type, direction, amount, balanceAfter, description -AutoSize | Out-String

Step "Alice's rewards (rewards-service)"
(Get-Json "$rewards/api/rewards/me" $alice.accessToken).content |
    Format-Table ruleCode, transactionAmount, rewardAmount -AutoSize | Out-String

Step "Monthly cashback leaderboard (Redis sorted set)"
(Get-Json "$rewards/api/rewards/leaderboard?limit=5" $alice.accessToken).entries | Format-Table -AutoSize | Out-String

Step "Admin lists cashback rules"
$admin = Post "$wallet/api/auth/login" @{ email = "admin@digitalwallet.dev"; password = "Admin@12345" }
Get-Json "$rewards/api/rules" $admin.accessToken |
    Format-Table code, transactionType, minAmount, cashbackPercent, maxReward, dailyLimitPerUser, active -AutoSize | Out-String

Step "Logout revokes Alice's token in BOTH services (shared Redis denylist)"
Invoke-RestMethod -Method Post -Uri "$wallet/api/auth/logout" -Headers @{ Authorization = "Bearer $($alice.accessToken)" } | Out-Null
foreach ($url in "$wallet/api/wallets/me", "$rewards/api/rewards/me") {
    try { Get-Json $url $alice.accessToken | Out-Null; "$url -> still accepted (unexpected)" }
    catch { "$url -> HTTP $($_.Exception.Response.StatusCode.value__)" }
}

if ($cashbackArrived -and [decimal]$balance -eq $expected) {
    Write-Host "`nDEMO PASSED" -ForegroundColor Green
} else {
    Write-Host "`nDEMO FAILED: balance $balance, expected $expected" -ForegroundColor Red
    exit 1
}
