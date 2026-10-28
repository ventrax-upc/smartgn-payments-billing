$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if(-not (Test-Path .env)){throw 'Copy .env.example to .env and configure local credentials first'}
docker compose up -d --build
if($LASTEXITCODE -ne 0){throw 'Startup failed'}
