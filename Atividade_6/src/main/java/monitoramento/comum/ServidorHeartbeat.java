// Ficheiro: src/main/java/monitoramento/comum/ServidorHeartbeat.java
package monitoramento.comum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Classe responsável por implementar um servidor de Heartbeat.
 * Esse servidor escuta em uma porta definida e responde a mensagens "PING"
 * com "PONG", permitindo verificar se o nó está ativo.
 */
public class ServidorHeartbeat implements Runnable {
    // Fornece dinamicamente o identificador do nó
    private final Supplier<Integer> idSupplier;
    // Fornece dinamicamente se o nó está ativo ou não
    private final Supplier<Boolean> isAtivoSupplier;
    // Porta onde o servidor vai escutar conexões
    private final int porta;
    // Callback executado assim que o socket do servidor é criado
    private final Consumer<ServerSocket> socketCallback;

    /**
     * Construtor do servidor de Heartbeat.
     *
     * @param idSupplier fornecedor do ID do nó
     * @param isAtivoSupplier fornecedor do estado ativo/inativo
     * @param porta porta onde o servidor ficará escutando
     * @param socketCallback callback para manipulação do socket do servidor
     */
    public ServidorHeartbeat(Supplier<Integer> idSupplier, Supplier<Boolean> isAtivoSupplier, int porta, Consumer<ServerSocket> socketCallback) {
        this.idSupplier = idSupplier;
        this.isAtivoSupplier = isAtivoSupplier;
        this.porta = porta;
        this.socketCallback = socketCallback;
    }

    @Override
    public void run() {
        // Tenta iniciar o servidor na porta especificada
        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            // Executa o callback com o socket criado
            socketCallback.accept(serverSocket);
            System.out.printf("[INFO] No %d: Servidor de Heartbeat iniciado na porta %d, aguardando pings.%n", idSupplier.get(), porta);

            // Loop principal do servidor, enquanto o nó estiver ativo
            while (isAtivoSupplier.get()) {
                try (Socket clientSocket = serverSocket.accept()) {
                    // Aceita uma conexão de cliente
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    String msg = in.readLine(); // Lê a mensagem enviada pelo cliente

                    // Se a mensagem recebida for "PING", responde com "PONG"
                    if ("PING".equals(msg)) {
                        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                        out.println("PONG");
                    }
                } catch (Exception e) {
                    // Se ocorrer erro durante o atendimento de um cliente
                    if (isAtivoSupplier.get()) {
                        System.err.printf("[ERRO] No %d: Erro no servidor de Heartbeat: %s%n", idSupplier.get(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Caso o servidor não consiga iniciar na porta definida
            if (isAtivoSupplier.get()) {
                System.err.printf("[ERRO] No %d: Nao foi possivel iniciar o servidor de Heartbeat na porta %d.%n", idSupplier.get(), porta);
            }
        }

        // Mensagem de encerramento do servidor
        System.out.printf("[INFO] No %d: Servidor de Heartbeat encerrado.%n", idSupplier.get());
    }
}
