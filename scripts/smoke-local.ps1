$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
python tools/smoke-local.py
if($LASTEXITCODE -ne 0){throw 'Integrated smoke verification failed'}
