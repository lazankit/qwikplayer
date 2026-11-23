# Builds QwikPlayer Web for Android -> android\out\QwikPlayerWeb.apk (no Gradle: javac + Android build-tools).
# Needs the JDK and Android SDK in %USERPROFILE%\android-tools (see setup-tools.ps1).
# The player script and start page are shared with the Windows app (..\web).
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$tools = "$env:USERPROFILE\android-tools"
$env:JAVA_HOME = "$tools\jdk"
$env:Path = "$tools\jdk\bin;$env:Path"
$bt = "$tools\sdk\build-tools\34.0.0"
$jar = "$tools\sdk\platforms\android-34\android.jar"
$out = "$src\out"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\assets", "$out\gen", "$out\classes", "$out\dex" | Out-Null
function Check($what) { if ($LASTEXITCODE) { throw "$what failed" } }

# Launcher icon at every screen density, from the QwikPlayer Web icon.
Add-Type -AssemblyName System.Drawing
$icon = [System.Drawing.Image]::FromFile("$src\icon.png")
foreach ($d in @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }.GetEnumerator()) {
  New-Item -ItemType Directory -Force "$out\res\mipmap-$($d.Key)" | Out-Null
  $b = New-Object System.Drawing.Bitmap $d.Value, $d.Value
  $g = [System.Drawing.Graphics]::FromImage($b); $g.InterpolationMode = 'HighQualityBicubic'
  $g.DrawImage($icon, 0, 0, $d.Value, $d.Value); $g.Dispose()
  $b.Save("$out\res\mipmap-$($d.Key)\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
}
$icon.Dispose()
Copy-Item "$src\res\*" "$out\res" -Recurse -Force   # adaptive + themed (monochrome) icon, colours
Copy-Item "$src\..\web\qwik-player.js", "$src\..\web\start.html" "$out\assets"

& "$bt\aapt2.exe" compile --dir "$out\res" -o "$out\res.zip"; Check 'aapt2 compile'
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$src\AndroidManifest.xml" -A "$out\assets" --java "$out\gen" "$out\res.zip"; Check 'aapt2 link'

$java = Get-ChildItem "$src\src", "$out\gen" -Recurse -Filter *.java | % FullName
& javac -source 8 -target 8 -bootclasspath "$jar;$bt\core-lambda-stubs.jar" -encoding UTF-8 -Xlint:-options -d "$out\classes" @java; Check 'javac'
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | % FullName
& "$bt\d8.bat" --min-api 26 --lib $jar --output "$out\dex" @classes; Check 'd8'

Push-Location "$out\dex"; & jar -uf "$out\base.apk" classes.dex; Pop-Location; Check 'add dex'
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"; Check 'zipalign'

# Signing. A release key must NOT fall back to a password that is published in this repository, so:
#   1. Release key (android\oranix-release.jks, git-ignored): the password comes from the environment variable
#      QWIKPLAYER_KEYSTORE_PASS (ORANIX_KEYSTORE_PASS still works) or from the first line of the untracked file
#      android\keystore.pass (git-ignored through android\.gitignore). Created on first build if missing.
#      Keep the key: updates must be signed with the same key as the installed copy.
#   2. No password supplied: the APK is signed with the standard Android DEBUG key
#      (%USERPROFILE%\.android\debug.keystore, password "android"). That is fine for installing on your own
#      phone and unsafe for distribution - it is not a release build, and the script says so.
# The file name and the key alias still say "oranix" (the app's old name) on purpose: they open the key that
# already exists; renaming them would quietly create a new key that cannot update an installed copy.
$pass = $env:QWIKPLAYER_KEYSTORE_PASS
if (!$pass) { $pass = $env:ORANIX_KEYSTORE_PASS }
$passFile = "$src\keystore.pass"
if (!$pass -and (Test-Path $passFile)) { $pass = (Get-Content $passFile -TotalCount 1).Trim() }
if ($pass) {
  $ks = "$src\oranix-release.jks"; $alias = 'oranix'
  if (!(Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
      -storepass $pass -keypass $pass -dname "CN=QwikPlayer, O=QwikPlayer"; Check 'keytool'
  }
  $kind = 'release key'
} else {
  Write-Warning 'No keystore password (QWIKPLAYER_KEYSTORE_PASS or android\keystore.pass): signing with the DEBUG key. Do not distribute this APK.'
  $ks = "$env:USERPROFILE\.android\debug.keystore"; $alias = 'androiddebugkey'; $pass = 'android'
  if (!(Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    & keytool -genkeypair -keystore $ks -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
      -storepass $pass -keypass $pass -dname "CN=Android Debug,O=Android,C=US"; Check 'keytool (debug)'
  }
  $kind = 'DEBUG key'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $alias --ks-pass "pass:$pass" --key-pass "pass:$pass" --out "$out\QwikPlayerWeb.apk" "$out\aligned.apk"; Check 'apksigner'
"APK ($kind): $out\QwikPlayerWeb.apk"
