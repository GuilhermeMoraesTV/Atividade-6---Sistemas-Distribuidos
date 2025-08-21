@echo off
echo "== Iniciando Cliente de Monitorização Multicast =="
set CLASSPATH=./bin

java -cp "%CLASSPATH%" monitoramento.ClienteMonitor

pause