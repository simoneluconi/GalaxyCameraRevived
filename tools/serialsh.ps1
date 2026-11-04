<#
.SYNOPSIS
  Run a command on the Galaxy Camera's bring-up serial console (USB ACM).

.DESCRIPTION
  Userdebug builds expose a root shell on /dev/ttyGS0, which Windows shows as
  a USB serial COM port. This script sends one command and prints its output,
  using an end marker to know when the command has finished.

.EXAMPLE
  .\serialsh.ps1 -Port COM12 -Command "getprop ro.lineage.version"
.EXAMPLE
  .\serialsh.ps1 -List
#>
param(
    [string]$Port,
    [string]$Command = "id",
    [int]$TimeoutSec = 30,
    [switch]$List
)

if ($List -or -not $Port) {
    Get-CimInstance Win32_SerialPort | Select-Object DeviceID, Description, PNPDeviceID | Format-Table -AutoSize
    Get-PnpDevice -PresentOnly -Class Ports | Select-Object Status, FriendlyName, InstanceId | Format-Table -AutoSize
    return
}

$sp = New-Object System.IO.Ports.SerialPort $Port, 115200, None, 8, One
$sp.ReadTimeout = 500
$sp.NewLine = "`n"
$sp.Open()
try {
    $id = [guid]::NewGuid().ToString('N').Substring(0, 8)
    # The shell prints "@@B<id>@@" and "@@E<id>@@", but the echoed command line
    # only contains '@@"B"<id>@@', so the printed markers are never confused
    # with the (possibly wrapped) echo of the command.
    $begin = "@@B$id@@"
    $end = "@@E$id@@"
    # Drain whatever the shell printed before (prompt, old output)
    Start-Sleep -Milliseconds 200
    $null = $sp.ReadExisting()
    # The tty expects a carriage return as Enter
    $sp.Write("echo '@@'B$id'@@'; $Command; echo '@@'E$id'@@'`r")
    $out = New-Object System.Text.StringBuilder
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $chunk = $sp.ReadExisting()
        if ($chunk) {
            [void]$out.Append($chunk)
            if ($out.ToString().Contains($end)) { break }
        } else {
            Start-Sleep -Milliseconds 100
        }
    }
    $text = $out.ToString() -replace "`r", ""
    $b = $text.IndexOf($begin)
    $e = $text.IndexOf($end)
    if ($b -lt 0 -or $e -lt 0) {
        Write-Warning "no complete output within $TimeoutSec s; raw output follows"
        return $text
    }
    $text.Substring($b + $begin.Length, $e - $b - $begin.Length).Trim("`n")
} finally {
    $sp.Close()
}
