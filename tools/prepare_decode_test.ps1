$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force '.tools' | Out-Null
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 4 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -movflags +faststart -y .tools/test-pattern.mp4
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg fixture generation failed' }
if (-not (Test-Path '.tools/test-tls.p12')) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -alias test -keyalg RSA -storetype PKCS12 -keystore .tools/test-tls.p12 -storepass cinema-test -keypass cinema-test -dname 'CN=localhost' -validity 3650 -ext 'SAN=IP:127.0.0.1'
    if ($LASTEXITCODE -ne 0) { throw 'Test TLS key generation failed' }
}
