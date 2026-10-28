$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$network = 'billing-test-' + [Guid]::NewGuid().ToString('N')
$db = $network + '-db'
try {
 docker network create $network | Out-Null
 docker run --rm -d --name $db --network $network -e POSTGRES_PASSWORD=local-test-password -e POSTGRES_DB=verification postgres:17.6 | Out-Null
 for($i=0;$i -lt 60;$i++){docker exec $db pg_isready -U postgres *> $null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Seconds 1}
 docker run --rm --network $network -e "TEST_DB_URL=jdbc:postgresql://${db}:5432/verification" -v "${root}:/workspace" -v "${root}/.local/m2:/root/.m2" -w /workspace maven:3.9.11-eclipse-temurin-25 mvn -B --no-transfer-progress verify
 if($LASTEXITCODE -ne 0){throw 'Verification failed'}
} finally {docker stop $db *> $null;docker network rm $network *> $null}
