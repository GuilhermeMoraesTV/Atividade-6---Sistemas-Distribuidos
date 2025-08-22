package monitoramento.autenticacao;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ServidorAutenticacao implements Runnable {
    private static final int PORTA_BASE_AUTENTICACAO = 9090;
    private static final long TEMPO_EXPIRACAO_TOKEN_MS = 300000; // 5 minutos

    // Mapa para armazenar o token e o tempo de sua criação
    private static final Map<String, Long> sessoesAtivas = new ConcurrentHashMap<>();

    private final Supplier<Integer> idSupplier;
    private final Supplier<Integer> coordenadorIdSupplier;
    private final Supplier<Boolean> isAtivoSupplier;
    private final Runnable registrarClienteCallback;
    private final Consumer<ServerSocket> socketCallback;

    public ServidorAutenticacao(Supplier<Integer> idSupplier,
                                Supplier<Integer> coordenadorIdSupplier,
                                Supplier<Boolean> isAtivoSupplier,
                                Runnable registrarClienteCallback,
                                Consumer<ServerSocket> socketCallback) {
        this.idSupplier = idSupplier;
        this.coordenadorIdSupplier = coordenadorIdSupplier;
        this.isAtivoSupplier = isAtivoSupplier;
        this.registrarClienteCallback = registrarClienteCallback;
        this.socketCallback = socketCallback;
    }

    @Override
    public void run() {
        // Tentar portas diferentes para evitar conflito
        int portaFinal = PORTA_BASE_AUTENTICACAO;
        ServerSocket serverSocket = null;

        for (int tentativa = 0; tentativa < 10; tentativa++) {
            try {
                portaFinal = PORTA_BASE_AUTENTICACAO + tentativa;
                serverSocket = new ServerSocket(portaFinal);
                break;
            } catch (Exception e) {
                System.err.printf("[AUTH] Porta %d ocupada, tentando %d...%n",
                        portaFinal, portaFinal + 1);
            }
        }

        if (serverSocket == null) {
            System.err.printf("[AUTH] P%d: Não foi possível encontrar porta disponível%n", idSupplier.get());
            return;
        }

        try {
            socketCallback.accept(serverSocket);
            System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao iniciado na porta %d.%n",
                    idSupplier.get(), portaFinal);

            while (isAtivoSupplier.get() && idSupplier.get().equals(coordenadorIdSupplier.get())) {
                try (Socket clientSocket = serverSocket.accept()) {
                    processarRequisicaoCliente(clientSocket);
                } catch (SocketException e) {
                    if (isAtivoSupplier.get()) {
                        System.err.printf("[AUTH] Erro no socket: %s%n", e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    System.err.printf("[AUTH] Erro ao processar cliente: %s%n", e.getMessage());
                }
            }
        } catch (Exception e) {
            if (isAtivoSupplier.get()) {
                System.err.printf("[AUTH] Erro no servidor de autenticação: %s%n", e.getMessage());
            }
        } finally {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (Exception e) {
                // Ignorar
            }
        }
        System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao encerrado.%n", idSupplier.get());
    }

    private void processarRequisicaoCliente(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String requisicao = in.readLine();
            if (requisicao == null) return;

            System.out.printf("[AUTH] Requisição recebida de %s: %s%n",
                    clientSocket.getRemoteSocketAddress(), requisicao);

            String[] partes = requisicao.split(":", 2);
            String comando = partes[0];
            String payload = partes.length > 1 ? partes[1] : "";

            switch (comando) {
                case "LOGIN":
                    processarLogin(payload, out);
                    break;
                case "VALIDATE":
                    processarValidacao(payload, out);
                    break;
                default:
                    out.println("ERRO: Comando desconhecido");
                    System.err.printf("[AUTH] Comando desconhecido: %s%n", comando);
                    break;
            }
        } catch (Exception e) {
            System.err.printf("[AUTH] Erro ao processar requisição: %s%n", e.getMessage());
        }
    }

    private void processarLogin(String credenciais, PrintWriter out) {
        if ("admin;admin".equals(credenciais)) {
            String token = UUID.randomUUID().toString();
            sessoesAtivas.put(token, System.currentTimeMillis());
            out.println("TOKEN:" + token);

            System.out.printf("[AUTH] Login SUCESSO! Token: %s (P%d)%n", token, idSupplier.get());

            // Garantir que o callback seja chamado
            if (registrarClienteCallback != null) {
                try {
                    registrarClienteCallback.run();
                    System.out.printf("[AUTH] Cliente registrado e relatórios ativados (P%d)%n", idSupplier.get());
                } catch (Exception e) {
                    System.err.printf("[AUTH] Erro ao registrar cliente: %s%n", e.getMessage());
                }
            }
        } else {
            out.println("ERRO: Credenciais invalidas");
            System.err.printf("[AUTH] Credenciais incorretas: %s%n", credenciais);
        }
    }

    private void processarValidacao(String token, PrintWriter out) {
        Long tempoCriacao = sessoesAtivas.get(token);

        if (tempoCriacao != null && (System.currentTimeMillis() - tempoCriacao) < TEMPO_EXPIRACAO_TOKEN_MS) {
            out.println("VALIDO");
        } else {
            sessoesAtivas.remove(token);
            out.println("EXPIRADO");
        }
    }
}