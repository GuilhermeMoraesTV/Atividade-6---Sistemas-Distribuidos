@echo off
chcp 65001 > nul
echo --- PASSO 1: Compilando e empacotando o projeto... ---
START "Compilando o Projeto..." /WAIT COMPILAR.bat

if not exist "target\sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar" (
    echo.
    echo ***** FALHA NA COMPILACAO! Ficheiro .jar nao foi criado. *****
    pause
    exit /b
)

echo.
echo --- PASSO 2: Iniciando a Simulacao dos Nós (Servidor)... ---
START "Simulador dos Nós" java -jar target/sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar

echo.
echo --- A aguardar 10 segundos para o cliente iniciar... ---
timeout /t 10 /nobreak > nul

echo.
echo --- PASSO 3: Iniciando o Cliente... ---
START "Cliente de Monitorização" java -cp target/sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar monitoramento.cliente.ClienteAutenticado

echo.
echo --- Todas as janelas foram iniciadas! ---
pause