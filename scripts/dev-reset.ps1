<#
.SYNOPSIS
  Rebuilds the LOCAL development environment from scratch: fresh Docker volumes (all DDL reloaded, Keycloak realm
  re-imported), dev tenant seed, reference data and - optionally - the sample data set.

.DESCRIPTION
  DESTROYS the local Docker volumes yourhrm_pgdata and yourhrm_keycloak-data (and the dev object store with -Wipe).
  Local development only. Steps:
    1. docker compose down -v ; up -d  (infra/docker)       -> DDL 000..007 loaded, realm "yourhrm" imported
    2. dev/seed-dev-tenant.sql   (owner = Keycloak user "admin")
    3. dev/seed-sample-reference.sql (admin units, banks, code lists, number series)
    4. with -SampleData: waits for the backend (SPRING_PROFILES_ACTIVE=dev, started by you) and runs
       scripts/sample-data/seed.mjs

.EXAMPLE
  ./scripts/dev-reset.ps1                 # asks for confirmation, resets DB + Keycloak
  ./scripts/dev-reset.ps1 -Force -SampleData
#>
param(
    [switch]$Force,
    [switch]$SampleData
)
# Windows PowerShell 5.1 turns native stderr (docker progress output) into errors under 'Stop': check exit codes instead.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $root 'infra/docker/docker-compose.yml'
$devRes = Join-Path $root 'backend/src/app/src/main/resources/dev'
$ownerIdentity = '018f0000-0000-7000-8000-0000000000ad'   # Keycloak user "admin" (infra/docker/keycloak/realm-yourhrm.json)

if (-not $Force) {
    $answer = Read-Host 'This DELETES all local YourHRM data (Docker volumes pgdata, keycloak-data). Type yes to continue'
    if ($answer -ne 'yes') { Write-Host 'Aborted.'; exit 1 }
}

Write-Host '== 1/4 Recreating Docker stack (DDL + Keycloak realm)'
docker compose -f $compose down -v
docker compose -f $compose up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

Write-Host '   waiting for the DDL load...'
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    $log = docker logs yhrm-postgres 2>&1 | Out-String
    if ($log -match 'ERROR:') { throw "DDL load failed:`n$log" }
} until ($log -match '\[yourhrm\] DDL loaded' -or (Get-Date) -gt $deadline)
if ($log -notmatch '\[yourhrm\] DDL loaded') { throw 'Timed out waiting for the DDL load' }
# initdb restarts the server once after the scripts: wait until it accepts connections again
do { Start-Sleep -Seconds 2; docker exec yhrm-postgres pg_isready -U postgres -d yhrm *> $null } until ($LASTEXITCODE -eq 0)

function Invoke-Psql([string]$file, [string[]]$extra = @()) {
    docker cp $file "yhrm-postgres:/tmp/$(Split-Path -Leaf $file)" | Out-Null
    docker exec yhrm-postgres psql -v ON_ERROR_STOP=1 -q -U postgres -d yhrm @extra -f "/tmp/$(Split-Path -Leaf $file)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "psql $file failed" }
}

Write-Host '== 2/4 Dev tenant seed (owner = Keycloak user admin)'
Invoke-Psql (Join-Path $devRes 'seed-dev-tenant.sql') @('-v', "owner_identity=$ownerIdentity")

Write-Host '== 3/4 Reference data (admin units, banks, code lists, number series)'
Invoke-Psql (Join-Path $devRes 'seed-sample-reference.sql')

Write-Host '   waiting for Keycloak...'
do {
    Start-Sleep -Seconds 3
    try { $ok = (Invoke-WebRequest -UseBasicParsing 'http://localhost:8180/realms/yourhrm/.well-known/openid-configuration').StatusCode -eq 200 } catch { $ok = $false }
} until ($ok)

if ($SampleData) {
    Write-Host '== 4/4 Sample data - (re)start the backend now if it is running: SPRING_PROFILES_ACTIVE=dev gradlew :app:bootRun'
    Write-Host '   waiting for http://localhost:8080/actuator/health ...'
    do {
        Start-Sleep -Seconds 5
        try { $ok = (Invoke-WebRequest -UseBasicParsing 'http://localhost:8080/actuator/health').StatusCode -eq 200 } catch { $ok = $false }
    } until ($ok)
    Start-Sleep -Seconds 5   # let the dev release job / provisioning finish
    node (Join-Path $root 'scripts/sample-data/seed.mjs')
} else {
    Write-Host '== Done. Restart the backend (SPRING_PROFILES_ACTIVE=dev), then: node scripts/sample-data/seed.mjs'
}
