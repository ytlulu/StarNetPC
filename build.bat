@echo off
REM Build lokal di Windows (butuh Python 3.10+ terinstall)
python -m pip install --upgrade pip pyinstaller
pyinstaller --noconfirm --onefile --windowed --name StarDevNett-Windows --icon assets\icon.ico --add-data "assets;assets" main.py
echo.
echo Selesai: dist\StarDevNett-Windows.exe
pause
