<#
.SYNOPSIS
    SciFiLauncher Laptop Agent (PowerShell edition)

.DESCRIPTION
    Runs on this Windows machine and lets the SciFiLauncher Android app view and control
    this screen remotely (over the internet, not just local WiFi), through the same
    Cloud Run backend Xenos already uses. Same wire protocol and same backend endpoint as
    the Python agent (laptop_agent/agent.py) - either one works with the same app.

    Unlike the Python version, this needs nothing installed - PowerShell + .NET already
    ship with Windows. Just download this file and run it.

    First run generates a pairing token and prints it. That token is the entire auth
    boundary for this connection - anyone who has it can view/control this PC while the
    agent is running, so treat it like a password. Delete config.json (shown at startup)
    and rerun to generate a new one if it ever leaks.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File agent.ps1
#>

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$BackendWsBase = "wss://elene-backend-717899371194.us-central1.run.app"
$FrameIntervalMs = 150       # ~6-7 fps, tuned for a phone data connection
$JpegQuality = 45
$MaxFrameWidth = 1000        # downscaled before encoding to keep frames small

$ConfigDir = Join-Path $env:APPDATA "SciFiLauncherAgent"
$ConfigFile = Join-Path $ConfigDir "config.json"

# ---- Win32 input via P/Invoke - no external modules needed ----
$Win32Source = @"
using System;
using System.Runtime.InteropServices;

public static class Win32Input {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
}
"@
Add-Type -TypeDefinition $Win32Source -ErrorAction SilentlyContinue

function Load-OrCreate-Token {
    New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
    if (Test-Path $ConfigFile) {
        try {
            $data = Get-Content $ConfigFile -Raw | ConvertFrom-Json
            if ($data.token -and $data.token.Length -ge 16) { return $data.token }
        } catch {}
    }
    # Lowercase hex only - matches the Python agent's token shape (no case-sensitivity,
    # no 0/O 1/l ambiguity typing this in on a phone keyboard).
    $bytes = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
    @{ token = $token } | ConvertTo-Json | Set-Content $ConfigFile
    return $token
}

function Print-PairingInfo([string]$Token) {
    Write-Host ("=" * 60)
    Write-Host "SciFiLauncher Laptop Agent (PowerShell)"
    Write-Host ("=" * 60)
    Write-Host "Pairing code (enter this in the app's Link to Laptop screen):`n"
    Write-Host "    $Token`n"
    Write-Host "Type it carefully - this build doesn't render a QR code, text entry only."
    Write-Host "Config/token stored at: $ConfigFile"
    Write-Host ("=" * 60)
    Write-Host "Leave this window open. Ctrl+C to stop.`n"
}

function Capture-FrameJpeg([int]$ScreenW, [int]$ScreenH) {
    $bmp = New-Object System.Drawing.Bitmap $ScreenW, $ScreenH
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(0, 0, 0, 0, $bmp.Size)
    $g.Dispose()

    $outBmp = $bmp
    if ($ScreenW -gt $MaxFrameWidth) {
        $ratio = $MaxFrameWidth / $ScreenW
        $newH = [int]($ScreenH * $ratio)
        $outBmp = New-Object System.Drawing.Bitmap $bmp, $MaxFrameWidth, $newH
        $bmp.Dispose()
    }

    $encoder = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
    $encParams = New-Object System.Drawing.Imaging.EncoderParameters 1
    $encParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality, [int64]$JpegQuality)

    $ms = New-Object System.IO.MemoryStream
    $outBmp.Save($ms, $encoder, $encParams)
    $outBmp.Dispose()
    return $ms.ToArray()
}

$KeyMap = @{
    "enter" = "{ENTER}"; "backspace" = "{BACKSPACE}"; "tab" = "{TAB}"
    "esc" = "{ESC}"; "escape" = "{ESC}"; "up" = "{UP}"; "down" = "{DOWN}"
    "left" = "{LEFT}"; "right" = "{RIGHT}"; "space" = " "; "delete" = "{DEL}"
    "home" = "{HOME}"; "end" = "{END}"
}

function Handle-Command($Cmd, [int]$ScreenW, [int]$ScreenH) {
    switch ($Cmd.type) {
        "mouse_move" {
            $x = [int]([double]$Cmd.x * $ScreenW)
            $y = [int]([double]$Cmd.y * $ScreenH)
            [Win32Input]::SetCursorPos($x, $y) | Out-Null
        }
        "mouse_click" {
            $x = [int]([double]$Cmd.x * $ScreenW)
            $y = [int]([double]$Cmd.y * $ScreenH)
            [Win32Input]::SetCursorPos($x, $y) | Out-Null
            if ($Cmd.button -eq "right") {
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero)
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [UIntPtr]::Zero)
            } else {
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
            }
        }
        "mouse_down" {
            $flag = if ($Cmd.button -eq "right") { [Win32Input]::MOUSEEVENTF_RIGHTDOWN } else { [Win32Input]::MOUSEEVENTF_LEFTDOWN }
            [Win32Input]::mouse_event($flag, 0, 0, 0, [UIntPtr]::Zero)
        }
        "mouse_up" {
            $flag = if ($Cmd.button -eq "right") { [Win32Input]::MOUSEEVENTF_RIGHTUP } else { [Win32Input]::MOUSEEVENTF_LEFTUP }
            [Win32Input]::mouse_event($flag, 0, 0, 0, [UIntPtr]::Zero)
        }
        "scroll" {
            [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_WHEEL, 0, 0, [uint32]([int]$Cmd.dy * 40), [UIntPtr]::Zero)
        }
        "key_text" {
            if ($Cmd.text) {
                # SendKeys treats +^%~(){} as special - escape literal text before sending.
                $escaped = [System.Windows.Forms.SendKeys]::Sanitize([string]$Cmd.text)
                [System.Windows.Forms.SendKeys]::SendWait($escaped)
            }
        }
        "key_press" {
            $mapped = $KeyMap[[string]$Cmd.key]
            if ($mapped) { [System.Windows.Forms.SendKeys]::SendWait($mapped) }
        }
    }
}

function Run-Session([string]$Token) {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $screenW = $bounds.Width
    $screenH = $bounds.Height

    $url = "$BackendWsBase/laptop/ws/agent/$Token"
    Write-Host "Connecting to $url ..."

    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $cts = New-Object System.Threading.CancellationTokenSource
    $ws.ConnectAsync([Uri]$url, $cts.Token).GetAwaiter().GetResult()
    Write-Host "Connected. Waiting for the phone to open Link to Laptop..."

    $infoMsg = @{ type = "info"; width = $screenW; height = $screenH } | ConvertTo-Json -Compress
    $infoBytes = [System.Text.Encoding]::UTF8.GetBytes($infoMsg)
    $ws.SendAsync([ArraySegment[byte]]$infoBytes, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()

    $phonePresent = @{ value = $false }
    $recvBuffer = New-Object byte[] 8192
    $lastFrameSent = [DateTime]::MinValue

    while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        # Non-blocking-ish receive: short-timeout poll so we can interleave sending frames
        # on the same thread without needing a second runspace - ClientWebSocket supports
        # one pending send and one pending receive concurrently, so alternating like this
        # per loop iteration is safe as long as neither op is left half-finished.
        $receiveTask = $ws.ReceiveAsync([ArraySegment[byte]]$recvBuffer, $cts.Token)
        $completed = $receiveTask.AsyncWaitHandle.WaitOne(30)

        if ($completed) {
            $result = $receiveTask.GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                break
            }
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Text -and $result.Count -gt 0) {
                $text = [System.Text.Encoding]::UTF8.GetString($recvBuffer, 0, $result.Count)
                try {
                    $data = $text | ConvertFrom-Json
                    switch ($data.type) {
                        "phone_connected" { $phonePresent.value = $true; Write-Host "[+] Phone connected." }
                        "phone_offline" { $phonePresent.value = $false; Write-Host "Waiting for the phone to open Link to Laptop..." }
                        "phone_disconnected" { $phonePresent.value = $false; Write-Host "[-] Phone disconnected." }
                        default { Handle-Command $data $screenW $screenH }
                    }
                } catch {}
            }
        }

        $now = Get-Date
        if ($phonePresent.value -and ($now - $lastFrameSent).TotalMilliseconds -ge $FrameIntervalMs) {
            try {
                $frame = Capture-FrameJpeg $screenW $screenH
                $ws.SendAsync([ArraySegment[byte]]$frame, [System.Net.WebSockets.WebSocketMessageType]::Binary, $true, $cts.Token).GetAwaiter().GetResult() | Out-Null
                $lastFrameSent = $now
            } catch {
                break
            }
        }
    }
}

# ---- main ----
$token = Load-OrCreate-Token
Print-PairingInfo $token
$backoff = 2
while ($true) {
    try {
        Run-Session $token
        $backoff = 2
    } catch {
        Write-Host "Connection lost ($($_.Exception.Message)). Reconnecting in $backoff s..."
    }
    Start-Sleep -Seconds $backoff
    $backoff = [Math]::Min($backoff * 2, 30)
}
