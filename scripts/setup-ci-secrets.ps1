<#
.SYNOPSIS
  Generate the release signing keystore and push the four signing secrets to
  GitHub Actions, so the build-android.yml workflow can produce signed APKs.

.DESCRIPTION
  Run this ONCE (and again only if you rotate the key). It:
    1. Finds keytool (from Android Studio's bundled JDK, JAVA_HOME, or PATH).
    2. Creates keystore.jks at the repo root if it doesn't exist (re-uses it if
       it does, so the signing key stays stable — a different key breaks updates).
    3. Writes keystore.properties for local release builds.
    4. Sets the GitHub Actions secrets: ANDROID_KEYSTORE_BASE64,
       ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.

  Requirements: gh CLI authenticated (gh auth status), and EITHER keytool (any
  JDK) OR openssl (bundled with Git for Windows at usr\bin\openssl.exe). On
  Windows-on-ARM with no JDK/Android Studio, the OpenSSL path is used
  automatically and produces a PKCS12 keystore the CI + Android signer accept.

  *** BACK UP keystore.jks + keystore.properties somewhere safe. ***
  If you lose them you cannot ship another update — Android only accepts an
  upgrade signed with the same key.

.NOTES
  Run from the repo root:  pwsh scripts/setup-ci-secrets.ps1
#>
param(
    [string]$Repo  = 'pcxnet/TeslaSync',
    [string]$Alias = 'teslasync'
)

$ErrorActionPreference = 'Stop'
$repoRoot   = Split-Path -Parent $PSScriptRoot
$jksPath    = Join-Path $repoRoot 'keystore.jks'
$propsPath  = Join-Path $repoRoot 'keystore.properties'

function Find-Keytool {
    @(
        (Get-Command keytool -ErrorAction SilentlyContinue).Source,
        (Join-Path $env:JAVA_HOME 'bin\keytool.exe'),
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr\bin\keytool.exe')
    ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}

function Find-OpenSsl {
    @(
        (Get-Command openssl -ErrorAction SilentlyContinue).Source,
        'C:\Program Files\Git\usr\bin\openssl.exe',
        'C:\Program Files\Git\mingw64\bin\openssl.exe'
    ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}

# Build a PKCS12 keystore with OpenSSL (no JDK needed). JDK 17 + Android's
# signer read PKCS12 fine; key password == store password (PKCS12 convention).
function New-KeystoreWithOpenSsl($ossl, $out, $alias, $pw) {
    $env:MSYS_NO_PATHCONV = '1'   # stop Git/MSYS mangling the -subj "/CN=..." string
    $keyPem  = Join-Path $env:TEMP 'tsk_key.pem'
    $certPem = Join-Path $env:TEMP 'tsk_cert.pem'
    & $ossl req -x509 -newkey rsa:2048 -keyout $keyPem -out $certPem -days 10000 -nodes -subj "/CN=TeslaSync/OU=pcxnet/O=pcxnet/C=AU" 2>&1 | Out-Null
    & $ossl pkcs12 -export -inkey $keyPem -in $certPem -out $out -name $alias -passout "pass:$pw" 2>&1 | Out-Null
    [System.IO.File]::Delete($keyPem)
    [System.IO.File]::Delete($certPem)
}

function New-KeystoreWithKeytool($keytool, $out, $alias, $pw) {
    & $keytool -genkeypair -v -keystore $out -alias $alias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $pw -keypass $pw `
        -dname "CN=TeslaSync, OU=pcxnet, O=pcxnet, C=AU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to generate the keystore." }
}

function New-StrongPassword {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    -join (1..28 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

# Confirm gh is authenticated.
gh auth status 1>$null 2>$null
if ($LASTEXITCODE -ne 0) { throw "gh is not authenticated. Run 'gh auth login' first." }

# Re-use existing credentials so the key stays stable across re-runs.
if (Test-Path $propsPath) {
    Write-Host "Reading existing keystore.properties (keeping the current key)."
    $props = Get-Content $propsPath | Where-Object { $_ -match '=' } | ForEach-Object {
        $k, $v = $_ -split '=', 2; [pscustomobject]@{ Key = $k.Trim(); Value = $v.Trim() }
    }
    $storePw = ($props | Where-Object Key -eq 'storePassword').Value
    $Alias   = ($props | Where-Object Key -eq 'keyAlias').Value
    $keyPw   = ($props | Where-Object Key -eq 'keyPassword').Value
} else {
    $storePw = New-StrongPassword
    $keyPw   = $storePw   # one password for store + key keeps CI verification simple
}

# Generate the keystore if missing (re-use it otherwise so the signing key stays stable).
if (-not (Test-Path $jksPath)) {
    $keytool = Find-Keytool
    $ossl    = Find-OpenSsl
    if ($keytool) {
        Write-Host "Generating keystore with keytool: $keytool"
        New-KeystoreWithKeytool $keytool $jksPath $Alias $storePw
    } elseif ($ossl) {
        Write-Host "No JDK/keytool found — generating PKCS12 keystore with OpenSSL: $ossl"
        New-KeystoreWithOpenSsl $ossl $jksPath $Alias $storePw
    } else {
        throw "Neither keytool nor openssl found. Install a JDK, or Git for Windows (it bundles openssl), then re-run."
    }
} else {
    Write-Host "Re-using existing keystore at $jksPath."
}

# Write keystore.properties for local release builds (git-ignored).
@"
storeFile=keystore.jks
storePassword=$storePw
keyAlias=$Alias
keyPassword=$keyPw
"@ | Set-Content -Path $propsPath -Encoding UTF8 -NoNewline
Write-Host "Wrote $propsPath"

# Push the four secrets to GitHub Actions.
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jksPath))
$b64 | gh secret set ANDROID_KEYSTORE_BASE64   --repo $Repo
gh secret set ANDROID_KEYSTORE_PASSWORD --repo $Repo --body $storePw
gh secret set ANDROID_KEY_ALIAS         --repo $Repo --body $Alias
gh secret set ANDROID_KEY_PASSWORD      --repo $Repo --body $keyPw

Write-Host ""
Write-Host "Done. Secrets set on $Repo:"  -ForegroundColor Green
Write-Host "  ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD"
Write-Host ""
Write-Host "*** BACK UP keystore.jks and keystore.properties now. ***" -ForegroundColor Yellow
Write-Host "Losing them means you can never ship a signed update to this app."
Write-Host "Trigger a build:  gh workflow run build-android.yml --repo $Repo"
