$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force .local/mqtt | Out-Null
$settings = @{}
if(Test-Path .env){foreach($line in Get-Content .env){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$Matches[1]]=$Matches[2]}}}
foreach($name in @('DB_PASSWORD','INTERNAL_SERVICE_TOKEN','JWT_SECRET','MQTT_PASSWORD','MQTT_SUPPORT_PASSWORD','MQTT_INSTALLATION_PASSWORD','TAX_KEY_PASSWORD','FIXTURE_ADMIN_TOKEN','BOOTSTRAP_SUPERADMIN_PASSWORD')){if(-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])){$settings[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}}
foreach($name in @('DELIVERY_ENCRYPTION_KEY','DEVICE_CREDENTIAL_KEY')){if(-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])){$settings[$name]=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}}
if(-not $settings.ContainsKey('CARD_ENCRYPTION_KEY') -or [string]::IsNullOrWhiteSpace($settings['CARD_ENCRYPTION_KEY'])){$settings['CARD_ENCRYPTION_KEY']=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}
$settings.GetEnumerator() | Sort-Object Name | ForEach-Object {"$($_.Name)=$($_.Value)"} | Set-Content .env
$env:TAX_KEY_PASSWORD=$settings['TAX_KEY_PASSWORD']
if(-not (Test-Path .local/tax.p12)){
 docker run --rm -e TAX_KEY_PASSWORD -v "${PWD}/.local:/keys" eclipse-temurin:25-jdk keytool -genkeypair -alias smartgn-local -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=SmartGN LOCAL TEST, O=SmartGN DEMO, C=PE' -storetype PKCS12 -keystore /keys/tax.p12 -storepass:env TAX_KEY_PASSWORD -keypass:env TAX_KEY_PASSWORD
 if($LASTEXITCODE -ne 0){throw 'Local signing key generation failed'}
}
$env:MQTT_PASSWORD=$settings['MQTT_PASSWORD'];$env:MQTT_SUPPORT_PASSWORD=$settings['MQTT_SUPPORT_PASSWORD'];$env:MQTT_INSTALLATION_PASSWORD=$settings['MQTT_INSTALLATION_PASSWORD']
docker run --rm -e MQTT_PASSWORD -e MQTT_SUPPORT_PASSWORD -e MQTT_INSTALLATION_PASSWORD -v "${PWD}/.local/mqtt:/config" --entrypoint sh eclipse-mosquitto:2 -c 'mosquitto_passwd -b -c /config/passwords billing-service "$MQTT_PASSWORD" && mosquitto_passwd -b /config/passwords support-service "$MQTT_SUPPORT_PASSWORD" && mosquitto_passwd -b /config/passwords installation-service "$MQTT_INSTALLATION_PASSWORD" && chmod 644 /config/passwords'
if($LASTEXITCODE -ne 0){throw 'Broker credentials generation failed'}
@'
listener 1883
allow_anonymous false
password_file /mosquitto/config/passwords
acl_file /mosquitto/config/acl
persistence true
persistence_location /mosquitto/data/
log_dest stdout
'@ | Set-Content .local/mqtt/mosquitto.conf
@'
user billing-service
topic write smartgn/events/payments/#
topic read smartgn/events/installation/#
topic read smartgn/events/support/#
user installation-service
topic write smartgn/events/installation/#
topic read smartgn/events/payments/#
user support-service
topic write smartgn/events/support/#
topic read smartgn/events/payments/#
'@ | Set-Content .local/mqtt/acl
Write-Output 'Local test keys and authenticated broker configuration prepared; secrets remain in ignored files.'
