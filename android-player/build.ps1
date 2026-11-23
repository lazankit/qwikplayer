# Builds QwikPlayer for Android -> android-player\out\QwikPlayer.apk (no Gradle: aapt2 + javac + d8 + apksigner).
# Uses the same toolchain as ..\android\build.ps1: JDK and Android SDK in %USERPROFILE%\android-tools
#   (build-tools 34.0.0, platforms\android-34\android.jar). minSdk 21, targetSdk 34, plain Java, framework APIs only.
# Signed with the standard Android DEBUG key (%USERPROFILE%\.android\debug.keystore, created if missing).
# That is right for installing on your own phone; it is not a release signature.
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$tools = "$env:USERPROFILE\android-tools"
$env:JAVA_HOME = "$tools\jdk"
$env:Path = "$tools\jdk\bin;$env:Path"
$bt = "$tools\sdk\build-tools\34.0.0"
$jar = "$tools\sdk\platforms\android-34\android.jar"
$out = "$src\out"
function Check($what) { if ($LASTEXITCODE) { throw "$what failed" } }
foreach ($p in @("$bt\aapt2.exe", "$bt\d8.bat", "$bt\apksigner.bat", $jar, "$tools\jdk\bin\javac.exe")) {
  if (!(Test-Path $p)) { throw "Missing $p - see android\build.ps1 / setup-tools.ps1 for the toolchain." }
}

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

# 1. resources (launcher PNGs, vector icons, styles) -> binary APK + R.java
& "$bt\aapt2.exe" compile --dir "$src\res" -o "$out\res.zip"; Check 'aapt2 compile'
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$src\AndroidManifest.xml" `
  --min-sdk-version 21 --target-sdk-version 34 --java "$out\gen" "$out\res.zip"; Check 'aapt2 link'

# 2. Java -> classes (-source 8: lambdas are desugared by d8 for API 21)
$java = Get-ChildItem "$src\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName
& javac -source 8 -target 8 -bootclasspath "$jar;$bt\core-lambda-stubs.jar" -encoding UTF-8 -Xlint:all -Xlint:-options -Xlint:-deprecation -d "$out\classes" @java; Check 'javac'

# 3. classes -> dex
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --min-api 21 --lib $jar --output "$out\dex" @classes; Check 'd8'

# 4. add dex, align, sign
Push-Location "$out\dex"; & jar -uf "$out\base.apk" classes.dex; $rc = $LASTEXITCODE; Pop-Location
if ($rc) { throw 'add dex failed' }
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"; Check 'zipalign'

$ks = "$env:USERPROFILE\.android\debug.keystore"
if (!(Test-Path $ks)) {
  New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
  & keytool -genkeypair -keystore $ks -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"; Check 'keytool'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
  --out "$out\QwikPlayer.apk" "$out\aligned.apk"; Check 'apksigner'
& "$bt\apksigner.bat" verify "$out\QwikPlayer.apk"; Check 'apksigner verify'
"APK (debug-signed): $out\QwikPlayer.apk"
