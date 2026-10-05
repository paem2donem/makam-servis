@echo off
title Makam Servis - Sunucu
chcp 65001 > nul
echo ========================================================
echo    Makam Servis (Kat Mutfaklari Surumu) Baslatiliyor...
echo ========================================================
echo.
echo Tarayicidan su adresleri kullanabilirsiniz:
echo - Ana Sayfa (Oda Secimi): http://localhost:8000
echo - Mutfak Panelleri:       http://localhost:8000/mutfak
echo - Yonetici Paneli:        http://localhost:8000/admin
echo.
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
pause
