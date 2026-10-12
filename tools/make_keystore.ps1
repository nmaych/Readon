$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Create the Readon release keystore and wire it into the build.
#
# The key is generated locally and never leaves this workspace. It is reused
# for every release so an installed app can be upgraded in place; a fresh key
# each build would force an uninstall first.
# ---------------------------------------------------------------------------

$root  = 'E:\deepseekworkspace\readon'
$keytool = 'E:\deepseekworkspace\androidtools\jdk\jdk-17.0.20.1+1\bin\keytool.exe'
$ks     = Join-Path $root 'keystore\readon-release.jks'
$props  = Join-Path $root 'app-project\keystore.properties'

New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null

if (-not (Test-Path $ks)) {
    Write-Output 'Generating release keystore...'
    & $keytool -genkeypair `
        -keystore $ks `
        -storetype JKS `
        -alias readon `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10950 `
        -storepass readon2026 `
        -keypass readon2026 `
        -dname 'CN=Readon, OU=Apps, O=Readon, L=Internet, ST=Web, C=CN' 2>&1 | Write-Output
} else {
    Write-Output 'Keystore already exists, reusing.'
}

# keystore.properties beside the app build file is the documented local-signing
# path: build.gradle.kts reads it when the environment variables are absent.
$rel = Split-Path $ks -Leaf
$relPath = Join-Path '..' $rel   # relative to app-project
@"
storeFile=readon-release.jks
storePassword=readon2026
keyAlias=readon
keyPassword=readon2026
"@ | Set-Content (Join-Path $root 'app-project\keystore.properties') -Encoding ASCII

# build.gradle.kts resolves storeFile relative to the root project, so the
# keystore itself is copied next to keystore.properties.
Copy-Item $ks (Join-Path $root 'app-project\readon-release.jks') -Force

Write-Output 'keystore.properties written. Release builds are now signed.'
