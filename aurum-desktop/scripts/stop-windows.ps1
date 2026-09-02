$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$connections = @(Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue)
if (-not $connections) {
    Write-Host 'Aurum is not running on port 8787.'
    exit 0
}

$processIds = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
Write-Host "Stopping Aurum on port 8787 (pid: $($processIds -join ', '))..."
foreach ($processId in $processIds) {
    Stop-Process -Id $processId -ErrorAction SilentlyContinue
}

for ($attempt = 0; $attempt -lt 20; $attempt += 1) {
    Start-Sleep -Milliseconds 500
    if (-not (Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue)) {
        Write-Host 'Aurum stopped.'
        exit 0
    }
}

Write-Host 'Graceful shutdown timed out; forcing.'
Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host 'Aurum stopped.'