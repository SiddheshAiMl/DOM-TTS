# iTantra ESP32 Firmware Flashing Script
# Automatically flashes compiled binary artifacts to COM6 (Node A) and COM7 (Node B)

$ErrorActionPreference = "Stop"

$PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildDir = Join-Path $PSScriptRoot "build"

$Esptool = Join-Path $env:LOCALAPPDATA "Arduino15\packages\esp32\tools\esptool_py\5.3.1\esptool.exe"
if (-not (Test-Path $Esptool)) {
    $searchRoot = Join-Path $env:LOCALAPPDATA "Arduino15"
    if (Test-Path $searchRoot) {
        $found = Get-ChildItem -Path $searchRoot -Recurse -Filter "esptool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $Esptool = $found.FullName
        }
    }
}

if (-not (Test-Path $Esptool)) {
    Write-Error "esptool.exe could not be located in $env:LOCALAPPDATA\Arduino15"
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   iTantra ESP32 Node Flashing (COM6 & COM7)" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Using esptool: $Esptool"
Write-Host "Using build artifacts from: $BuildDir"

$Bootloader = Join-Path $BuildDir "esp32_lora_bridge.ino.bootloader.bin"
$Partitions = Join-Path $BuildDir "esp32_lora_bridge.ino.partitions.bin"
$BootApp0   = Join-Path $BuildDir "boot_app0.bin"
$AppBin     = Join-Path $BuildDir "esp32_lora_bridge.ino.bin"

foreach ($file in @($Bootloader, $Partitions, $BootApp0, $AppBin)) {
    if (-not (Test-Path $file)) {
        Write-Error "Required binary artifact not found: $file. Please compile firmware first."
    }
}

# 1. Flash Node A (COM6)
Write-Host "`n--- [1/2] Flashing Node A on COM6 ---" -ForegroundColor Yellow
& $Esptool --chip esp32 --port COM6 --baud 921600 --before default-reset --after hard-reset write-flash -z `
    --flash-mode dio --flash-freq 80m --flash-size 4MB `
    0x1000 $Bootloader `
    0x8000 $Partitions `
    0xe000 $BootApp0 `
    0x10000 $AppBin

if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to flash Node A on COM6"
}
Write-Host "Node A (COM6) flashed successfully!" -ForegroundColor Green

# 2. Flash Node B (COM7)
Write-Host "`n--- [2/2] Flashing Node B on COM7 ---" -ForegroundColor Yellow
& $Esptool --chip esp32 --port COM7 --baud 921600 --before default-reset --after hard-reset write-flash -z `
    --flash-mode dio --flash-freq 80m --flash-size 4MB `
    0x1000 $Bootloader `
    0x8000 $Partitions `
    0xe000 $BootApp0 `
    0x10000 $AppBin

if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to flash Node B on COM7"
}
Write-Host "Node B (COM7) flashed successfully!" -ForegroundColor Green

Write-Host "`n==================================================" -ForegroundColor Cyan
Write-Host "Both ESP32 nodes flashed successfully!" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
