# iTantra LoRa Bidirectional Link & Serial PING/PONG Test
# Tests serial responsiveness and over-the-air communication between COM6 and COM7

param (
    [string]$PortA = "COM6",
    [string]$PortB = "COM7",
    [int]$BaudRate = 115200
)

$ErrorActionPreference = "Stop"

function Open-SerialPort ($portName, $baud) {
    $sp = New-Object System.IO.Ports.SerialPort $portName, $baud, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
    $sp.ReadTimeout = 2000
    $sp.WriteTimeout = 2000
    $sp.DtrEnable = $true
    $sp.RtsEnable = $false
    $sp.Open()
    Start-Sleep -Milliseconds 200
    $sp.DiscardInBuffer()
    $sp.DiscardOutBuffer()
    return $sp
}

function Read-LinesAvailable ($sp, $timeoutMs = 3000) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $buf = ""
    while ($sw.ElapsedMilliseconds -lt $timeoutMs) {
        if ($sp.BytesToRead -gt 0) {
            $buf += $sp.ReadExisting()
        }
        Start-Sleep -Milliseconds 50
    }
    $lines = $buf -split "\r?\n" | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 }
    return $lines
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   iTantra Hardware PING & LoRa Link Verification " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Node A Port: $PortA"
Write-Host "Node B Port: $PortB"
Write-Host "Baud Rate:   $BaudRate"

$spA = $null
$spB = $null
$allTestsPassed = $true

try {
    Write-Host "`n[STEP 1] Opening serial connections..." -ForegroundColor Yellow
    $spA = Open-SerialPort $PortA $BaudRate
    $spB = Open-SerialPort $PortB $BaudRate
    Write-Host "Opened $PortA and $PortB successfully. Settling 1s..." -ForegroundColor Green
    Start-Sleep -Milliseconds 1000
    $spA.DiscardInBuffer()
    $spB.DiscardInBuffer()

    # 1. Hardware PING on Node A
    Write-Host "`n[STEP 2] Testing PING on Node A ($PortA)..." -ForegroundColor Yellow
    $spA.WriteLine("PING")
    Start-Sleep -Milliseconds 200
    $respA = Read-LinesAvailable $spA 2000
    $pongA = $respA | Where-Object { $_ -match "\[PONG\]" }
    Write-Host "Node A Output: $($respA -join ' | ')"
    if (-not $pongA) {
        Write-Host "FAILED: Node A ($PortA) did not return [PONG]." -ForegroundColor Red
        $allTestsPassed = $false
    } else {
        Write-Host "Node A PING/PONG: SUCCESS ($pongA)" -ForegroundColor Green
    }

    # 2. Hardware PING on Node B
    Write-Host "`n[STEP 3] Testing PING on Node B ($PortB)..." -ForegroundColor Yellow
    $spB.WriteLine("PING")
    Start-Sleep -Milliseconds 200
    $respB = Read-LinesAvailable $spB 2000
    $pongB = $respB | Where-Object { $_ -match "\[PONG\]" }
    Write-Host "Node B Output: $($respB -join ' | ')"
    if (-not $pongB) {
        Write-Host "FAILED: Node B ($PortB) did not return [PONG]." -ForegroundColor Red
        $allTestsPassed = $false
    } else {
        Write-Host "Node B PING/PONG: SUCCESS ($pongB)" -ForegroundColor Green
    }

    # 3. STATS query
    Write-Host "`n[STEP 4] Querying STATS..." -ForegroundColor Yellow
    $spA.WriteLine("STATS")
    Start-Sleep -Milliseconds 100
    $statsA = Read-LinesAvailable $spA 1000
    Write-Host "Node A Stats: $($statsA -join ' | ')"

    $spB.WriteLine("STATS")
    Start-Sleep -Milliseconds 100
    $statsB = Read-LinesAvailable $spB 1000
    Write-Host "Node B Stats: $($statsB -join ' | ')"

    # 4. Bidirectional LoRa Link Test: Node A -> Node B
    Write-Host "`n[STEP 5] Testing Over-The-Air LoRa TX: Node A -> Node B..." -ForegroundColor Yellow
    $spB.DiscardInBuffer()
    $testPayloadA = "HELLO_FROM_NODE_A_" + (Get-Date -Format "HHmmss")
    $spA.WriteLine("TX:$testPayloadA")
    Write-Host "Sent from Node A: TX:$testPayloadA"

    $rxB = Read-LinesAvailable $spB 3000
    Write-Host "Node B Received: $($rxB -join ' | ')"
    $matchB = $rxB | Where-Object { $_ -match $testPayloadA -or $_ -match "\[LORA RX\]" }
    if ($matchB) {
        Write-Host "Over-the-air A -> B: SUCCESS!" -ForegroundColor Green
    } else {
        Write-Host "FAILED: Node B did not report packet reception from Node A." -ForegroundColor Red
        $allTestsPassed = $false
    }

    # 5. Bidirectional LoRa Link Test: Node B -> Node A
    Write-Host "`n[STEP 6] Testing Over-The-Air LoRa TX: Node B -> Node A..." -ForegroundColor Yellow
    $spA.DiscardInBuffer()
    $testPayloadB = "HELLO_FROM_NODE_B_" + (Get-Date -Format "HHmmss")
    $spB.WriteLine("TX:$testPayloadB")
    Write-Host "Sent from Node B: TX:$testPayloadB"

    $rxA = Read-LinesAvailable $spA 3000
    Write-Host "Node A Received: $($rxA -join ' | ')"
    $matchA = $rxA | Where-Object { $_ -match $testPayloadB -or $_ -match "\[LORA RX\]" }
    if ($matchA) {
        Write-Host "Over-the-air B -> A: SUCCESS!" -ForegroundColor Green
    } else {
        Write-Host "FAILED: Node A did not report packet reception from Node B." -ForegroundColor Red
        $allTestsPassed = $false
    }

    Write-Host "`n==================================================" -ForegroundColor Cyan
    if ($allTestsPassed) {
        Write-Host "Hardware Verification Complete: ALL TESTS PASSED!" -ForegroundColor Green
        Write-Host "==================================================" -ForegroundColor Cyan
        exit 0
    } else {
        Write-Host "Hardware Verification FAILED: One or more checks failed!" -ForegroundColor Red
        Write-Host "==================================================" -ForegroundColor Cyan
        exit 1
    }

} finally {
    if ($spA -and $spA.IsOpen) {
        $spA.Close()
        $spA.Dispose()
    }
    if ($spB -and $spB.IsOpen) {
        $spB.Close()
        $spB.Dispose()
    }
}
