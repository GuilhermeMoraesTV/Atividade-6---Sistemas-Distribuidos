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
    private static final int PORTA_BASE_AUTENTICACAO = 9090; // Porta base para o servidor de autenticação
    private static final long TEMPO_EXPIRACAO_TOKEN_MS = 300000; // 5 minutos de validade do token

    // Mapa para armazenar tokens ativos e o horário em que foram criados
    private static final Map<String, Long> sessoesAtivas = new ConcurrentHashMap<>();

    // Fornecedores e callbacks para integração com o sistema principal
    private final Supplier<Integer> idSupplier; // Fornece o ID do processo atual
    private final Supplier<Integer> coordenadorIdSupplier; // Fornece o ID do coordenador
    private final Supplier<Boolean> isAtivoSupplier; // Indica se o processo está ativo
    private final Runnable registrarClienteCallback; // Callback para registrar cliente autenticado
    private final Consumer<ServerSocket> socketCallback; // Callback ao iniciar o socket

    // Construtor recebe dependências externas para integração
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
        // Define a porta inicial
        int portaFinal = PORTA_BASE_AUTENTICACAO;
        ServerSocket serverSocket = null;

        // Tentar abrir o servidor em até 10 portas diferentes, caso a inicial esteja ocupada
        for (int tentativa = 0; tentativa < 10; tentativa++) {
            try {
                portaFinal = PORTA_BASE_AUTENTICACAO + tentativa;
                serverSocket = new ServerSocket(portaFinal);
                break; // Sucesso ao abrir socket
            } catch (Exception e) {
                System.err.printf("[AUTH] Porta %d ocupada, tentando %d...%n",
                        portaFinal, portaFinal + 1);
            }
        }

        // Caso não consiga abrir nenhuma porta
        if (serverSocket == null) {
            System.err.printf("[AUTH] P%d: Não foi possível encontrar porta disponível%n", idSupplier.get());
            return;
        }

        try {
            // Callback para notificar que o socket foi aberto
            socketCallback.accept(serverSocket);
            System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao iniciado na porta %d.%n",
                    idSupplier.get(), portaFinal);

            // O servidor de autenticação só roda enquanto o processo for ativo e for o coordenador
            while (isAtivoSupplier.get() && idSupplier.get().equals(coordenadorIdSupplier.get())) {
                try (Socket clientSocket = serverSocket.accept()) {
                    // Processa a requisição recebida do cliente
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
                // Fecha o socket caso ainda esteja aberto
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (Exception e) {
                // Ignorar erros ao fechar
            }
        }
        System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao encerrado.%n", idSupplier.get());
    }

    // Método responsável por tratar as requisições recebidas do cliente
    private void processarRequisicaoCliente(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            // Lê a requisição enviada pelo cliente
            String requisicao = in.readLine();
            if (requisicao == null) return;

            System.out.printf("[AUTH] Requisição recebida de %s: %s%n",
                    clientSocket.getRemoteSocketAddress(), requisicao);

            // Divide o comando do payload (ex: LOGIN:usuario;senha)
            String[] partes = requisicao.split(":", 2);
            String comando = partes[0]; // LOGIN ou VALIDATE
            String payload = partes.length > 1 ? partes[1] : "";

            // Identifica o comando recebido e processa
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

    // Processa o login do cliente
    private void processarLogin(String credenciais, PrintWriter out) {
        // Exemplo fixo de autenticação: admin;admin
        if ("admin;admin".equals(credenciais)) {
            // Gera um token único e registra com a hora de criação
            String token = UUID.randomUUID().toString();
            sessoesAtivas.put(token, System.currentTimeMillis());
            out.println("TOKEN:" + token);

            System.out.printf("[AUTH] Login SUCESSO! Token: %s (P%d)%n", token, idSupplier.get());

            // Executa o callback de registro de cliente autenticado
            if (registrarClienteCallback != null) {
                try {
                    registrarClienteCallback.run();
                    System.out.printf("[AUTH] Cliente registrado e relatórios ativados (P%d)%n", idSupplier.get());
                } catch (Exception e) {
                    System.err.printf("[AUTH] Erro ao registrar cliente: %s%n", e.getMessage());
                }
            }
        } else {
            // Credenciais inválidas
            out.println("ERRO: Credenciais invalidas");
            System.err.printf("[AUTH] Credenciais incorretas: %s%n", credenciais);
        }
    }

    // Valida se um token ainda é válido ou se expirou
    private void processarValidacao(String token, PrintWriter out) {
        Long tempoCriacao = sessoesAtivas.get(token);

        // Se o token existe e não passou do tempo de expiração
        if (tempoCriacao != null && (System.currentTimeMillis() - tempoCriacao) < TEMPO_EXPIRACAO_TOKEN_MS) {
            out.println("VALIDO");
        } else {
            // Token inválido ou expirado
            sessoesAtivas.remove(token);
            out.println("EXPIRADO");
        }
    }
}
