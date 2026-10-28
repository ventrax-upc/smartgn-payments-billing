param([switch]$Integrated)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
& ./scripts/prepare-local.ps1
if($Integrated){docker compose -f compose.integration.yml up -d --build}else{docker compose up -d --build}
if($LASTEXITCODE -ne 0){throw 'Startup failed'}
