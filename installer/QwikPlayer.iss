; QwikPlayer installer (Inno Setup 6). Build with ..\build.ps1 -Installer
; Installs QwikPlayer + QwikPlayer Web for the current user (no admin needed), with Start menu entries,
; video file registration, and an uninstaller in Settings > Apps.
;
; The app was called Oranix before. This installer upgrades an Oranix install in place, so the AppId
; below must never change, and the old names in the "from the Oranix days" blocks are there on purpose:
; they are how the old files, shortcuts and registry entries are found. Do not rename them.

#define AppVersion "2.0.0"

[Setup]
; Same AppId as the Oranix installer: Windows then sees one app, updated, not two side by side.
AppId={{6B1E8F3A-2C4D-4E7A-9B5F-3A8D1C2E7F90}
AppName=QwikPlayer
AppVersion={#AppVersion}
AppVerName=QwikPlayer {#AppVersion}
AppPublisher=QwikPlayer
AppComments=Play More. Worry Less.
; Outside AppData on purpose: the live-edit tools (..\live.ps1) can update it directly.
; New installs only: an upgrade stays in the folder it was installed to (UsePreviousAppDir is on by default).
DefaultDirName={%USERPROFILE}\QwikPlayer
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
; Windows 10 1809 or later.
MinVersion=10.0.17763
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputBaseFilename=QwikPlayerSetup
SetupIconFile=..\player\launcher\qwikplayer.ico
UninstallDisplayIcon={app}\Player\QwikPlayer.exe
UninstallDisplayName=QwikPlayer (Player + Web)
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
ChangesAssociations=yes
CloseApplications=yes

[Tasks]
Name: desktopicon; Description: "Create desktop shortcuts"; GroupDescription: "Shortcuts:"; Flags: unchecked
Name: assoc; Description: "Offer QwikPlayer for video files (.mkv, .mp4, ...)"; GroupDescription: "Video files:"

[InstallDelete]
; Space freed on upgrade: the second ffmpeg copy, uosc's Linux/macOS helpers, libmpv for the other CPU.
Type: filesandordirs; Name: "{app}\Player\alass\ffmpeg\bin"
Type: files; Name: "{app}\Player\config\scripts\uosc\bin\ziggy-darwin"
Type: files; Name: "{app}\Player\config\scripts\uosc\bin\ziggy-linux"
Type: filesandordirs; Name: "{app}\Player\libmpv\arm64"; Check: not IsArm64
; Leftovers from the earlier hand-made setup
Type: files; Name: "{userprograms}\Oranix.lnk"
Type: files; Name: "{localappdata}\Oranix\Oranix.exe"
Type: files; Name: "{localappdata}\Oranix\oranix.ico"
; From the Oranix days: files and shortcuts an upgrade would otherwise leave next to the renamed ones.
; The two scripts matter most: mpv runs every script in config\scripts, so a leftover copy would run
; a second subtitle engine and a second start screen.
Type: files; Name: "{app}\Player\config\scripts\oranix-idle.lua"
Type: files; Name: "{app}\Player\config\scripts\oranix-subs.lua"
Type: files; Name: "{app}\Player\Oranix Player.exe"
Type: files; Name: "{app}\Player\oranix-player.ico"
Type: files; Name: "{app}\Web\Oranix Web.exe"
Type: files; Name: "{app}\Web\oranix-player.js"
Type: files; Name: "{app}\Web\oranix-web.ico"
Type: files; Name: "{userprograms}\Oranix Player.lnk"
Type: files; Name: "{userprograms}\Oranix Web.lnk"
Type: files; Name: "{userdesktop}\Oranix Player.lnk"
Type: files; Name: "{userdesktop}\Oranix Web.lnk"

[Files]
; libmpv: the ARM64 copy is installed only on ARM64 PCs (an x64 PC never loads it). ARM64 PCs keep both,
; as before, because the player picks its DLL by the architecture of the process it runs as.
Source: "..\dist\Player\*"; DestDir: "{app}\Player"; Excludes: "\libmpv\arm64\*"; Flags: recursesubdirs ignoreversion
Source: "..\dist\Player\libmpv\arm64\*"; DestDir: "{app}\Player\libmpv\arm64"; Flags: ignoreversion; Check: IsArm64
Source: "..\dist\Web\*"; DestDir: "{app}\Web"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{userprograms}\QwikPlayer"; Filename: "{app}\Player\QwikPlayer.exe"; Comment: "Play videos on this PC"
Name: "{userprograms}\QwikPlayer Web"; Filename: "{app}\Web\QwikPlayer Web.exe"; Comment: "Watch web video with QwikPlayer controls"
Name: "{userdesktop}\QwikPlayer"; Filename: "{app}\Player\QwikPlayer.exe"; Tasks: desktopicon
Name: "{userdesktop}\QwikPlayer Web"; Filename: "{app}\Web\QwikPlayer Web.exe"; Tasks: desktopicon

[Registry]
; From the Oranix days: remove the old file-type registration, or the player shows up twice under
; Default apps and "Open with" (once pointing at an exe that no longer exists). Delete only, nothing is
; created here, and it runs whether or not the "assoc" task is ticked.
Root: HKCU; Subkey: "Software\Classes\Oranix.video"; ValueType: none; Flags: deletekey
Root: HKCU; Subkey: "Software\Oranix"; ValueType: none; Flags: deletekey
Root: HKCU; Subkey: "Software\RegisteredApplications"; ValueType: none; ValueName: "Oranix"; Flags: deletevalue dontcreatekey
#define Unassoc(Ext) \
  'Root: HKCU; Subkey: "Software\Classes\' + Ext + '\OpenWithProgids"; ValueType: none; ValueName: "Oranix.video"; Flags: deletevalue dontcreatekey' + NewLine
#emit Unassoc(".mkv") + Unassoc(".mp4") + Unassoc(".avi") + Unassoc(".webm") + Unassoc(".mov") + Unassoc(".m4v") + Unassoc(".wmv") + Unassoc(".flv") + Unassoc(".ts")

Root: HKCU; Subkey: "Software\Classes\QwikPlayer.video"; ValueType: string; ValueData: "QwikPlayer video"; Flags: uninsdeletekey; Tasks: assoc
Root: HKCU; Subkey: "Software\Classes\QwikPlayer.video\DefaultIcon"; ValueType: string; ValueData: """{app}\Player\QwikPlayer.exe"",0"; Tasks: assoc
Root: HKCU; Subkey: "Software\Classes\QwikPlayer.video\shell\open\command"; ValueType: string; ValueData: """{app}\Player\QwikPlayer.exe"" ""%1"""; Tasks: assoc
Root: HKCU; Subkey: "Software\QwikPlayer"; Flags: uninsdeletekeyifempty; Tasks: assoc
Root: HKCU; Subkey: "Software\QwikPlayer\Capabilities"; ValueType: string; ValueName: "ApplicationName"; ValueData: "QwikPlayer"; Flags: uninsdeletekey; Tasks: assoc
Root: HKCU; Subkey: "Software\QwikPlayer\Capabilities"; ValueType: string; ValueName: "ApplicationDescription"; ValueData: "Play More. Worry Less."; Tasks: assoc
Root: HKCU; Subkey: "Software\RegisteredApplications"; ValueType: string; ValueName: "QwikPlayer"; ValueData: "Software\QwikPlayer\Capabilities"; Flags: uninsdeletevalue; Tasks: assoc
#define Assoc(Ext) \
  'Root: HKCU; Subkey: "Software\QwikPlayer\Capabilities\FileAssociations"; ValueType: string; ValueName: "' + Ext + '"; ValueData: "QwikPlayer.video"; Tasks: assoc' + NewLine + \
  'Root: HKCU; Subkey: "Software\Classes\' + Ext + '\OpenWithProgids"; ValueType: none; ValueName: "QwikPlayer.video"; Flags: uninsdeletevalue; Tasks: assoc' + NewLine
#emit Assoc(".mkv") + Assoc(".mp4") + Assoc(".avi") + Assoc(".webm") + Assoc(".mov") + Assoc(".m4v") + Assoc(".wmv") + Assoc(".flv") + Assoc(".ts")

[Run]
; Windows does not let an installer carry a "default app" choice over to the renamed file type, so after
; an upgrade from Oranix the box is ticked: whoever had the player as the default can pick it again.
Filename: "ms-settings:defaultapps?registeredAppUser=QwikPlayer"; Description: "Choose QwikPlayer as the default for video files"; Flags: postinstall shellexec skipifsilent unchecked; Tasks: assoc; Check: not HadOranixFileTypes
Filename: "ms-settings:defaultapps?registeredAppUser=QwikPlayer"; Description: "Choose QwikPlayer as the default for video files again (the rename reset it)"; Flags: postinstall shellexec skipifsilent; Tasks: assoc; Check: HadOranixFileTypes
Filename: "{app}\Web\QwikPlayer Web.exe"; Description: "Open QwikPlayer Web"; Flags: postinstall nowait skipifsilent unchecked
Filename: "{app}\Player\QwikPlayer.exe"; Description: "Open QwikPlayer"; Flags: postinstall nowait skipifsilent

[Code]
// Noted before [Registry] removes it: was the pre-rename file type (Oranix.video) registered on this PC?
var OranixFileTypes: Boolean;

function WebView2Installed(): Boolean;
var pv: String;
begin
  pv := '';
  if not RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', pv) then
    if not RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', pv) then
      pv := '';
  Result := (pv <> '') and (pv <> '0.0.0.0');
end;

function InitializeSetup(): Boolean;
var rel: Cardinal; err: Integer;
begin
  OranixFileTypes := RegKeyExists(HKEY_CURRENT_USER, 'Software\Classes\Oranix.video');
  Result := True;
  // The apps are .NET Framework 4.8 programs (release 528040 or later). Windows 10 1903+ has it built in.
  if not RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full', 'Release', rel) or (rel < 528040) then begin
    SuppressibleMsgBox('QwikPlayer needs the Microsoft .NET Framework 4.8, which this PC does not have yet.' + #13#10#13#10 +
      'Install it from https://go.microsoft.com/fwlink/?linkid=2088631 (or run Windows Update), then run this setup again.', mbError, MB_OK, IDOK);
    Result := False;
    exit;
  end;
  // Not fatal: the Player plays videos without it; only the library page and QwikPlayer Web need it.
  if not WebView2Installed() then begin
    if SuppressibleMsgBox('The Microsoft Edge WebView2 Runtime is not installed on this PC.' + #13#10#13#10 +
      'QwikPlayer will still install and play videos, but the library page and QwikPlayer Web need the runtime. ' +
      'Open the download page for the Evergreen bootstrapper now? (You can also install it after setup.)', mbInformation, MB_YESNO, IDNO) = IDYES then
      ShellExec('open', 'https://go.microsoft.com/fwlink/p/?LinkId=2124703', '', '', SW_SHOWNORMAL, ewNoWait, err);
  end;
end;

function HadOranixFileTypes(): Boolean;
begin
  Result := OranixFileTypes;
end;
