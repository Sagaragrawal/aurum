$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
    } elseif (Get-Command choco -ErrorAction SilentlyContinue) {
        choco install nodejs-lts -y
    } else {
        throw 'Node.js 22+ is required. Install winget or Chocolatey, then run this script again.'
    }

    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machinePath;$userPath"
}

$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$npmCommand = Get-Command npm -ErrorAction SilentlyContinue
if (-not $nodeCommand -or -not $npmCommand) {
    throw 'Node.js/npm was installed but is not available yet. Close this terminal, open a new one, and run scripts\run-windows.bat again.'
}

$nodeMajor = [int]((& $nodeCommand.Source --version).TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 22) {
    throw "Node.js 22 or newer is required; found $(& $nodeCommand.Source --version)."
}

$env:NPM_CONFIG_PROGRESS = 'true'
$env:NPM_CONFIG_AUDIT = 'false'
$env:NPM_CONFIG_FUND = 'false'
$env:NPM_CONFIG_FETCH_TIMEOUT = '30000'
$env:NPM_CONFIG_FETCH_RETRIES = '1'

if (-not (Test-Path 'node_modules/playwright')) {
    npm ci --no-audit --no-fund
}
npx playwright install chromium firefox
if (Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host 'Aurum is already running at http://localhost:8787'
    exit 0
}
Write-Host 'Starting Aurum at http://localhost:8787'
node src/app/server.js