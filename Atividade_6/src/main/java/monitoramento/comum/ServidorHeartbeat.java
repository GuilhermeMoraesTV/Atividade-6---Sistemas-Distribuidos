// Ficheiro: src/main/java/monitoramento/comum/ServidorHeartbeat.java
package monitoramento.comum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ServidorHeartbeat implements Runnable {
    private final Supplier<Integer> idSupplier;
    private final Supplier<Boolean> isAtivoSupplier;
    private final int porta;
    private final Consumer<ServerSocket> socketCallback;

    public ServidorHeartbeat(Supplier<Integer> idSupplier, Supplier<Boolean> isAtivoSupplier, int porta, Consumer<ServerSocket> socketCallback) {
        this.idSupplier = idSupplier;
        this.isAtivoSupplier = isAtivoSupplier;
        this.porta = porta;
        this.socketCallback = socketCallback;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            socketCallback.accept(serverSocket);
            System.out.printf("[INFO] No %d: Servidor de Heartbeat iniciado na porta %d, aguardando pings.%n", idSupplier.get(), porta);

            while (isAtivoSupplier.get()) {
                try (Socket clientSocket = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    String msg = in.readLine();
                    if ("PING".equals(msg)) {
                        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                        out.println("PONG");
                    }
                } catch (Exception e) {
                    if (isAtivoSupplier.get()) {
                        System.err.printf("[ERRO] No %d: Erro no servidor de Heartbeat: %s%n", idSupplier.get(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (isAtivoSupplier.get()) {
                System.err.printf("[ERRO] No %d: Nao foi possivel iniciar o servidor de Heartbeat na porta %d.%n", idSupplier.get(), porta);
            }
        }
        System.out.printf("[INFO] No %d: Servidor de Heartbeat encerrado.%n", idSupplier.get());
    }
}