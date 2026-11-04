<#
.SYNOPSIS
  Copy a small file to the Galaxy Camera over the bring-up serial console.

.DESCRIPTION
  Gzips the file, sends it base64-encoded in lines shorter than the tty's
  4096-byte canonical line limit, decodes it on the device with toybox
  base64 and gzip, makes it executable and checks its SHA-1. A 4.6 MB APEX
  took about 8 minutes uncompressed; compression helps most on binaries.

.EXAMPLE
  .\serialpush.ps1 -Port COM10 -Local .\gd1_egltest -Remote /data/local/tmp/gd1_egltest
#>
param(
    [Parameter(Mandatory)] [string]$Port,
    [Parameter(Mandatory)] [string]$Local,
    [Parameter(Mandatory)] [string]$Remote
)

$bytes = [IO.File]::ReadAllBytes((Resolve-Path $Local))
$ms = New-Object IO.MemoryStream
$gz = New-Object IO.Compression.GZipStream($ms, [IO.Compression.CompressionLevel]::Optimal)
$gz.Write($bytes, 0, $bytes.Length)
$gz.Close()
$b64 = [Convert]::ToBase64String($ms.ToArray())
$sha1 = (Get-FileHash -Algorithm SHA1 (Resolve-Path $Local)).Hash.ToLower()

$sp = New-Object System.IO.Ports.SerialPort $Port, 115200, None, 8, One
$sp.Open()
function Send-Line([string]$line) {
    $sp.Write("$line`r")
    # Wait for the shell prompt to come back before the next line
    $buf = ''
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and -not $buf.Contains('#')) {
        $buf += $sp.ReadExisting()
        Start-Sleep -Milliseconds 20
    }
}
try {
    Start-Sleep -Milliseconds 200
    $null = $sp.ReadExisting()
    Send-Line "rm -f $Remote.b64"
    $chunk = 2048
    $start = Get-Date
    $lastPct = -1
    Write-Host ("serialpush: {0} KB, {1} KB to send after gzip+base64" -f [int]($bytes.Length / 1KB), [int]($b64.Length / 1KB))
    for ($i = 0; $i -lt $b64.Length; $i += $chunk) {
        $part = $b64.Substring($i, [Math]::Min($chunk, $b64.Length - $i))
        Send-Line "echo '$part' >> $Remote.b64"
        Write-Progress -Activity "serialpush $Local" -PercentComplete (100 * $i / $b64.Length)
        # Plain text progress every 5%, so it also shows in a captured log
        $pct = [int][Math]::Floor(100 * ($i + $part.Length) / $b64.Length)
        if ($pct -ge $lastPct + 5 -or $pct -eq 100) {
            $lastPct = $pct
            $elapsed = ((Get-Date) - $start).TotalSeconds
            $eta = if ($pct -gt 0) { [int]($elapsed * (100 - $pct) / $pct) } else { 0 }
            Write-Host ("serialpush: {0,3}%  elapsed {1}s  remaining ~{2}s" -f $pct, [int]$elapsed, $eta)
        }
    }
    Send-Line "base64 -d $Remote.b64 | gzip -d > $Remote && rm $Remote.b64 && chmod 0755 $Remote"
} finally {
    $sp.Close()
}
$remoteSha = & (Join-Path $PSScriptRoot 'serialsh.ps1') -Port $Port -Command "sha1sum $Remote | cut -d' ' -f1"
if ($remoteSha.Trim() -eq $sha1) { "copied $Local -> $Remote ($($bytes.Length) bytes, sha1 OK)" }
else { Write-Error "sha1 mismatch: local $sha1, remote $remoteSha" }
