# OSSM serial monitor — logs ESP32 serial output (incl. streaming telemetry) to a file.
# Opening the port resets the ESP32 once (CP210x auto-reset); after that it streams live.
param(
    [string]$Port = "COM11",
    [int]$Baud = 115200,
    [string]$LogFile = "C:\Users\mikae\Documents\Ossm\Ossm\tools\serial_live.log"
)

# Truncate previous log
"" | Set-Content -Path $LogFile -Encoding utf8

$sp = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$sp.DtrEnable = $false
$sp.RtsEnable = $false
$sp.ReadTimeout = 500
try {
    $sp.Open()
    # Clean reset into run mode so we boot firmware (not bootloader) and capture everything.
    $sp.RtsEnable = $true
    Start-Sleep -Milliseconds 150
    $sp.RtsEnable = $false

    $buffer = ""
    while ($true) {
        try {
            $chunk = $sp.ReadExisting()
            if ($chunk) {
                $buffer += $chunk
                # Flush complete lines to the log with a host timestamp
                while ($buffer.Contains("`n")) {
                    $idx = $buffer.IndexOf("`n")
                    $line = $buffer.Substring(0, $idx).TrimEnd("`r")
                    $buffer = $buffer.Substring($idx + 1)
                    if ($line.Length -gt 0) {
                        $ts = (Get-Date).ToString("HH:mm:ss.fff")
                        Add-Content -Path $LogFile -Value "$ts | $line" -Encoding utf8
                    }
                }
            }
        } catch [System.TimeoutException] { }
        Start-Sleep -Milliseconds 50
    }
} finally {
    if ($sp.IsOpen) { $sp.Close() }
    $sp.Dispose()
}
