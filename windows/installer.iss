; Installer StarDevNett untuk Windows (Inno Setup 6)
; Dibangun oleh GitHub Actions: lihat .github/workflows/windows.yml
#ifndef AppVersion
  #define AppVersion "1.2"
#endif

[Setup]
AppId={{6E1B7B3A-5C0E-4E2B-9B52-5D7A3C0E1A11}
AppName=StarDevNett
AppVersion={#AppVersion}
AppPublisher=StarDev
DefaultDirName={autopf}\StarDevNett
DefaultGroupName=StarDevNett
DisableProgramGroupPage=yes
; Tanpa admin: terpasang per-user di %LOCALAPPDATA%\Programs (bisa diubah ke semua user lewat dialog)
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\out
OutputBaseFilename=StarDevNett-Setup
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\StarDevNett.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Buat ikon di Desktop"; GroupDescription: "Pintasan:"

[Files]
; aplikasi (PyInstaller onedir)
Source: "dist\StarDevNett\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
; adb ikut terpasang -> mode USB langsung jalan, tanpa file tambahan
Source: "platform-tools\*"; DestDir: "{app}\platform-tools"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\StarDevNett"; Filename: "{app}\StarDevNett.exe"
Name: "{autodesktop}\StarDevNett"; Filename: "{app}\StarDevNett.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\StarDevNett.exe"; Description: "Jalankan StarDevNett"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; hentikan adb server supaya folder bisa terhapus bersih
Filename: "{app}\platform-tools\adb.exe"; Parameters: "kill-server"; Flags: runhidden skipifdoesntexist; RunOnceId: "AdbKill"
