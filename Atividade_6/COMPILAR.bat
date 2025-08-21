@echo off
chcp 65001 > nul
echo --- Limpando e empacotando o projeto com Maven... ---
mvn clean package

if %errorlevel% neq 0 (
    echo.
    echo ***** FALHA NA COMPILACAO! *****
    pause
) else (
    echo.
    echo Compilacao e empacotamento finalizados com SUCESSO!
    timeout /t 3 > nul
)
exit