[CmdletBinding()]
param(
    [string]$CloudflaredPath,
    [string]$PythonPath,
    [ValidateRange(1024, 65535)]
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = Join-Path $projectRoot ".runtime"
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

if (-not $CloudflaredPath) {
    $installedCloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
    $bundledCloudflared = Join-Path $projectRoot "..\deploy-tools\cloudflared.exe"
    if ($installedCloudflared) {
        $CloudflaredPath = $installedCloudflared.Source
    } elseif (Test-Path -LiteralPath $bundledCloudflared) {
        $CloudflaredPath = (Resolve-Path -LiteralPath $bundledCloudflared).Path
    } else {
        throw "cloudflared was not found. Install the official Windows binary or pass -CloudflaredPath."
    }
}

if (-not $PythonPath) {
    $projectVenv = Join-Path $projectRoot ".venv\Scripts\python.exe"
    $workspaceVenv = Join-Path $projectRoot "..\kuber-venv\Scripts\python.exe"
    if (Test-Path -LiteralPath $projectVenv) {
        $PythonPath = (Resolve-Path -LiteralPath $projectVenv).Path
    } elseif (Test-Path -LiteralPath $workspaceVenv) {
        $PythonPath = (Resolve-Path -LiteralPath $workspaceVenv).Path
    } else {
        throw "A Kuber Python virtual environment was not found. Pass -PythonPath."
    }
}

$portClient = [System.Net.Sockets.TcpClient]::new()
try {
    $portCheck = $portClient.ConnectAsync("127.0.0.1", $Port)
    if ($portCheck.Wait(300) -and $portClient.Connected) {
        throw "Port $Port is already in use. Choose another -Port value."
    }
} catch [System.Net.Sockets.SocketException] {
    # Connection refusal is the expected state before Kuber starts.
} finally {
    $portClient.Dispose()
}

$randomBytes = [byte[]]::new(32)
$randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $randomGenerator.GetBytes($randomBytes)
} finally {
    $randomGenerator.Dispose()
}
$apiToken = [Convert]::ToHexString($randomBytes).ToLowerInvariant()

$cloudStdout = Join-Path $runtimeRoot "cloudflared.stdout.log"
$cloudStderr = Join-Path $runtimeRoot "cloudflared.stderr.log"
$backendStdout = Join-Path $runtimeRoot "backend.stdout.log"
$backendStderr = Join-Path $runtimeRoot "backend.stderr.log"
$tokenPath = Join-Path $runtimeRoot "api-token.txt"
$urlPath = Join-Path $runtimeRoot "public-url.txt"
foreach ($runtimeFile in @($cloudStdout, $cloudStderr, $backendStdout, $backendStderr, $tokenPath, $urlPath)) {
    if (Test-Path -LiteralPath $runtimeFile) {
        Remove-Item -LiteralPath $runtimeFile -Force
    }
}

$cloudflared = $null
$backend = $null
try {
    $cloudflared = Start-Process -FilePath $CloudflaredPath `
        -ArgumentList @("tunnel", "--no-autoupdate", "--protocol", "http2", "--url", "http://127.0.0.1:$Port") `
        -RedirectStandardOutput $cloudStdout `
        -RedirectStandardError $cloudStderr `
        -WindowStyle Hidden `
        -PassThru

    $publicUrl = $null
    $tunnelDeadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not $publicUrl -and [DateTime]::UtcNow -lt $tunnelDeadline) {
        if ($cloudflared.HasExited) {
            throw "cloudflared exited before creating the HTTPS tunnel. Review $cloudStderr."
        }
        $tunnelLog = ""
        foreach ($logPath in @($cloudStdout, $cloudStderr)) {
            if (Test-Path -LiteralPath $logPath) {
                $tunnelLog += Get-Content -LiteralPath $logPath -Raw -ErrorAction SilentlyContinue
            }
        }
        $urlMatch = [regex]::Match($tunnelLog, "https://[a-z0-9-]+\.trycloudflare\.com")
        if ($urlMatch.Success) {
            $publicUrl = $urlMatch.Value
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $publicUrl) {
        throw "Timed out waiting for a TryCloudflare HTTPS address. Review $cloudStderr."
    }

    $publicUri = [Uri]$publicUrl
    $env:KUBER_ENVIRONMENT = "production"
    $env:KUBER_API_TOKEN = $apiToken
    $env:KUBER_PUBLIC_BASE_URL = $publicUrl
    $env:KUBER_ALLOWED_HOSTS = $publicUri.Host
    $env:KUBER_ENABLE_LIVE_ORDERS = "false"
    $env:PYTHONPATH = Join-Path $projectRoot "src"

    [System.IO.File]::WriteAllText($tokenPath, $apiToken)
    [System.IO.File]::WriteAllText($urlPath, $publicUrl)

    $backend = Start-Process -FilePath $PythonPath `
        -ArgumentList @(
            "-m", "uvicorn", "kuber.api.app:app",
            "--host", "127.0.0.1",
            "--port", $Port.ToString(),
            "--proxy-headers",
            "--forwarded-allow-ips", "127.0.0.1"
        ) `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $backendStdout `
        -RedirectStandardError $backendStderr `
        -WindowStyle Hidden `
        -PassThru

    $originReady = $false
    $originDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not $originReady -and [DateTime]::UtcNow -lt $originDeadline) {
        if ($backend.HasExited) {
            throw "Kuber backend exited during startup. Review $backendStderr."
        }
        try {
            $originResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 2
            $originReady = $originResponse.StatusCode -eq 200
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $originReady) {
        throw "Kuber backend did not become healthy. Review $backendStderr."
    }

    Start-Sleep -Seconds 5
    Clear-DnsClientCache -ErrorAction SilentlyContinue
    $remoteReady = $false
    $remoteDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while (-not $remoteReady -and [DateTime]::UtcNow -lt $remoteDeadline) {
        if ($cloudflared.HasExited -or $backend.HasExited) {
            throw "The backend or HTTPS tunnel stopped during remote verification."
        }
        try {
            $remoteResponse = Invoke-WebRequest -Uri "$publicUrl/health" -UseBasicParsing -TimeoutSec 10
            $remoteReady = $remoteResponse.StatusCode -eq 200
        } catch {
            Clear-DnsClientCache -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
        }
    }
    if (-not $remoteReady) {
        throw "Public HTTPS health check did not become ready. Review the .runtime logs."
    }

    $webSocketUrl = $publicUrl -replace "^https://", "wss://"
    $webSocket = [System.Net.WebSockets.ClientWebSocket]::new()
    $webSocket.Options.SetRequestHeader("Authorization", "Bearer $apiToken")
    $webSocketTimeout = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(15))
    try {
        $null = $webSocket.ConnectAsync(
            [Uri]"$webSocketUrl/market/stream/NIFTY",
            $webSocketTimeout.Token
        ).GetAwaiter().GetResult()
        $webSocketBuffer = [byte[]]::new(4096)
        $webSocketSegment = [ArraySegment[byte]]::new($webSocketBuffer)
        $webSocketMessage = $webSocket.ReceiveAsync(
            $webSocketSegment,
            $webSocketTimeout.Token
        ).GetAwaiter().GetResult()
        $webSocketText = [Text.Encoding]::UTF8.GetString(
            $webSocketBuffer,
            0,
            $webSocketMessage.Count
        )
        if ($webSocketText -notmatch '"kind"\s*:\s*"stream_status"') {
            throw "Kuber WSS endpoint did not return its authenticated stream status."
        }
    } finally {
        if ($webSocket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $null = $webSocket.CloseAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                "verified",
                [Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()
        }
        $webSocket.Dispose()
        $webSocketTimeout.Dispose()
    }

    Write-Output "KUBER_HTTPS_STATUS=READY"
    Write-Output "KUBER_HTTPS_URL=$publicUrl"
    Write-Output "KUBER_API_TOKEN_FILE=$tokenPath"
    Write-Output "KUBER_LIVE_ORDERS=DISABLED"
    Write-Output "KUBER_WSS_STATUS=READY"
    Write-Output "Keep this process running. Press Ctrl+C to stop the HTTPS tunnel and clear the trial token."

    while (-not $cloudflared.HasExited -and -not $backend.HasExited) {
        Start-Sleep -Seconds 2
    }
    throw "The backend or HTTPS tunnel stopped unexpectedly. Review the .runtime logs."
} finally {
    foreach ($process in @($backend, $cloudflared)) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path -LiteralPath $tokenPath) {
        Remove-Item -LiteralPath $tokenPath -Force
    }
    if ($randomBytes) {
        [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    }
    $apiToken = $null
}
