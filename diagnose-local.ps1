param(
    [Parameter(Mandatory = $false)]
    [string]$ProjectRoot = "D:\Brainserve-connect-Appointment-System"
)

$ErrorActionPreference = "Continue"

function Write-Check {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $status = if ($Passed) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail)
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port)
    try {
        return (Test-NetConnection -ComputerName $HostName -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
    } catch {
        return $false
    }
}

$root = (Resolve-Path $ProjectRoot).Path
$backendEnv = Join-Path $root "backend\.env"
$frontendEnv = Join-Path $root ".env.local"

Write-Host "BrainServe local service diagnosis"
Write-Host "Project: $root"
Write-Host ""

Write-Check "PostgreSQL port" (Test-TcpPort "localhost" 5432) "localhost:5432"
Write-Check "Redis port" (Test-TcpPort "localhost" 6379) "localhost:6379"
Write-Check "Kafka port" (Test-TcpPort "localhost" 9092) "localhost:9092"
Write-Check "MinIO port" (Test-TcpPort "localhost" 9000) "localhost:9000"
Write-Check "Backend port" (Test-TcpPort "localhost" 8080) "localhost:8080"

if (Test-Path $backendEnv) {
    Write-Check "backend/.env" $true "file exists"

    $values = @{}
    Get-Content $backendEnv | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
        $parts = $line -split "=", 2
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }

    $required = @(
        "DB_PASSWORD",
        "JWT_SECRET",
        "PII_ENCRYPTION_KEY",
        "QR_PASS_SIGNING_SECRET",
        "SYSTEM_ADMIN_DEFAULT_PASSWORD",
        "ARCHIVE_ENCRYPTION_KEYS",
        "S3_ACCESS_KEY",
        "S3_SECRET_KEY"
    )

    foreach ($name in $required) {
        $value = $values[$name]
        $valid = -not [string]::IsNullOrWhiteSpace($value) -and -not $value.StartsWith("CHANGE_ME")
        Write-Check $name $valid $(if ($valid) { "configured" } else { "missing, blank, or still CHANGE_ME" })
    }
} else {
    Write-Check "backend/.env" $false "create backend/.env from backend/.env.example"
}

if (Test-Path $frontendEnv) {
    $apiLine = Get-Content $frontendEnv |
        Where-Object { $_ -match '^\s*NEXT_PUBLIC_API_BASE_URL=' } |
        Select-Object -First 1
    $apiOk = $apiLine -match 'http://localhost:8080/api/v1/?\s*$'
    Write-Check ".env.local API URL" $apiOk $(if ($apiLine) { $apiLine.Trim() } else { "NEXT_PUBLIC_API_BASE_URL is missing" })
} else {
    Write-Check ".env.local" $false "frontend environment file is missing"
}

try {
    $health = Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 5
    Write-Check "Backend health" ($health.status -eq "UP") ("status=" + $health.status)
} catch {
    Write-Check "Backend health" $false $_.Exception.Message
}

try {
    $response = Invoke-WebRequest "http://localhost:8080/api-docs" -TimeoutSec 5 -UseBasicParsing
    Write-Check "Backend HTTP response" ($response.StatusCode -eq 200) ("HTTP " + $response.StatusCode)
} catch {
    Write-Check "Backend HTTP response" $false $_.Exception.Message
}

Write-Host ""
Write-Host "A login request that stops at exactly 20 seconds means the frontend aborted it."
Write-Host "When PostgreSQL credentials are wrong or the pool cannot obtain a connection,"
Write-Host "the backend may wait longer than the frontend timeout. Check the backend terminal"
Write-Host "for Hikari, PostgreSQL, Redis, Flyway, or authentication errors."