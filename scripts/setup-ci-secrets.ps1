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

  Requirements: gh CLI authenticated (gh auth status), and a JDK/keytool
  (installing Android Studio provides one at jbr\bin\keytool.exe).

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
    $candidates = @(
        (Get-Command keytool -ErrorAction SilentlyContinue).Source,
        (Join-Path $env:JAVA_HOME 'bin\keytool.exe'),
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr\bin\keytool.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    if (-not $candidates) {
        throw "keytool not found. Install Android Studio (it ships a JDK at jbr\bin\keytool.exe) or set JAVA_HOME, then re-run."
    }
    return $candidates[0]
}

function New-StrongPassword {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    -join (1..28 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

# Confirm gh is authenticated.
gh auth status 1>$null 2>$null
if ($LASTEXITCODE -ne 0) { throw "gh is not authenticated. Run 'gh auth login' first." }

$keytool = Find-Keytool
Write-Host "Using keytool: $keytool"

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

# Generate the keystore if it doesn't exist yet.
if (-not (Test-Path $jksPath)) {
    Write-Host "Generating new keystore at $jksPath (alias '$Alias')..."
    & $keytool -genkeypair -v `
        -keystore $jksPath `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $storePw -keypass $keyPw `
        -dname "CN=TeslaSync, OU=pcxnet, O=pcxnet, L=, S=, C=AU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to generate the keystore." }
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
