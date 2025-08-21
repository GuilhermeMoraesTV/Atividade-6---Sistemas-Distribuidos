package monitoramento.autenticacao;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ServidorAutenticacao implements Runnable {
    private static final int PORTA_AUTENTICACAO = 9090;
    private static final long TEMPO_EXPIRACAO_TOKEN_MS = 300000; // 5 minutos

    // Mapa para armazenar o token e o tempo de sua criação
    private static final Map<String, Long> sessoesAtivas = new ConcurrentHashMap<>();

    private final Supplier<Integer> idSupplier;
    private final Supplier<Integer> coordenadorIdSupplier;
    private final Supplier<Boolean> isAtivoSupplier;
    private final Runnable registrarClienteCallback;
    private final Consumer<ServerSocket> socketCallback;

    public ServidorAutenticacao(Supplier<Integer> idSupplier, Supplier<Integer> coordenadorIdSupplier, Supplier<Boolean> isAtivoSupplier, Runnable registrarClienteCallback, Consumer<ServerSocket> socketCallback) {
        this.idSupplier = idSupplier;
        this.coordenadorIdSupplier = coordenadorIdSupplier;
        this.isAtivoSupplier = isAtivoSupplier;
        this.registrarClienteCallback = registrarClienteCallback;
        this.socketCallback = socketCallback;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORTA_AUTENTICACAO)) {
            socketCallback.accept(serverSocket);
            System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao iniciado na porta %d.%n", idSupplier.get(), PORTA_AUTENTICACAO);

            while (isAtivoSupplier.get() && idSupplier.get().equals(coordenadorIdSupplier.get())) {
                try (Socket clientSocket = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);

                    String requisicao = in.readLine(); // Formato: "LOGIN:admin;admin" ou "VALIDATE:token"
                    if (requisicao == null) continue;

                    String[] partes = requisicao.split(":", 2);
                    String comando = partes[0];
                    String payload = partes.length > 1 ? partes[1] : "";

                    if ("LOGIN".equals(comando) && "admin;admin".equals(payload)) {
                        String token = UUID.randomUUID().toString();
                        sessoesAtivas.put(token, System.currentTimeMillis());
                        out.println("TOKEN:" + token);
                        System.out.printf("[AUTH] Lider P%d: Token gerado para cliente.%n", idSupplier.get());
                        registrarClienteCallback.run();
                    } else if ("VALIDATE".equals(comando)) {
                        String token = payload;
                        Long tempoCriacao = sessoesAtivas.get(token);

                        if (tempoCriacao != null && (System.currentTimeMillis() - tempoCriacao) < TEMPO_EXPIRACAO_TOKEN_MS) {
                            out.println("VALIDO");
                        } else {
                            sessoesAtivas.remove(token);
                            out.println("EXPIRADO");
                        }
                    } else {
                        out.println("ERRO: Comando ou credenciais invalidas");
                    }
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        System.out.printf("[AUTH] Lider P%d: Servidor de Autenticacao encerrado.%n", idSupplier.get());
    }
}