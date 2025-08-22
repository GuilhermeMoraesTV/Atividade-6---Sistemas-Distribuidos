package monitoramento.cliente;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class ClienteAutenticado {
    private static final int PORTA_BASE_AUTENTICACAO = 9090;
    private static final String ENDERECO_LIDER = "127.0.0.1";
    private static final int MAX_TENTATIVAS_AUTH = 5;
    private static final long ATRASO_TENTATIVAS_MS = 3000;

    public static void main(String[] args) {
        System.out.println("[CLIENTE] Sistema de Monitoramento - Cliente Autenticado");
        System.out.println("[CLIENTE] Iniciando processo de autenticação...");

        while (true) {
            boolean autenticado = false;
            String tokenAtual = null;

            // Loop de tentativas de autenticação
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_AUTH; tentativa++) {
                System.out.printf("[CLIENTE] Tentando autenticar (tentativa %d de %d)...%n",
                        tentativa, MAX_TENTATIVAS_AUTH);

                // Tentar múltiplas portas
                for (int portaOffset = 0; portaOffset < 10; portaOffset++) {
                    int porta = PORTA_BASE_AUTENTICACAO + portaOffset;

                    try (Socket socket = new Socket(ENDERECO_LIDER, porta);
                         PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                        System.out.printf("[CLIENTE] Tentando porta %d...%n", porta);

                        out.println("LOGIN:admin;admin");
                        String resposta = in.readLine();

                        if (resposta != null && resposta.startsWith("TOKEN:")) {
                            tokenAtual = resposta.substring(6);
                            System.out.printf("[CLIENTE] ✓ AUTENTICAÇÃO SUCESSO! Token: %s (porta %d)%n",
                                    tokenAtual, porta);
                            autenticado = true;
                            break;
                        } else {
                            System.err.printf("[CLIENTE] Falha na autenticação porta %d: %s%n", porta, resposta);
                        }
                    } catch (Exception e) {
                        // Falha silenciosa, tentar próxima porta
                    }
                }

                if (autenticado) break;

                System.err.printf("[CLIENTE] Nenhuma porta de auth disponível, aguardando %dms...%n", ATRASO_TENTATIVAS_MS);
                try {
                    Thread.sleep(ATRASO_TENTATIVAS_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            if (autenticado && tokenAtual != null) {
                try {
                    System.out.println("\n[CLIENTE] ✓ Autenticação concluída! Iniciando monitoramento...");

                    // Iniciar thread de validação periódica do token
                    String finalTokenAtual = tokenAtual;
                    Thread threadValidacao = new Thread(() -> validarTokenPeriodicamente(finalTokenAtual));
                    threadValidacao.setDaemon(true);
                    threadValidacao.start();

                    // Aguardar um pouco antes de iniciar monitor
                    Thread.sleep(2000);

                    // Iniciar o monitor
                    ClienteMonitor.main(null);

                } catch (SocketTimeoutException e) {
                    System.out.println("[CLIENTE] Timeout no monitoramento. Tentando encontrar novo líder...");
                } catch (Exception e) {
                    System.err.println("[CLIENTE] Erro no monitoramento: " + e.getMessage());
                }
            } else {
                System.err.printf("[CLIENTE] ❌ Falha na autenticação após %d tentativas%n", MAX_TENTATIVAS_AUTH);
                System.out.println("[CLIENTE] Tentando novamente em 20 segundos...");
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void validarTokenPeriodicamente(String token) {
        while (true) {
            try {
                Thread.sleep(60000); // Validar a cada 1 minuto

                // Tentar várias portas para validação também
                boolean validado = false;
                for (int portaOffset = 0; portaOffset < 10; portaOffset++) {
                    int porta = PORTA_BASE_AUTENTICACAO + portaOffset;

                    try (Socket socket = new Socket(ENDERECO_LIDER, porta);
                         PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                        out.println("VALIDATE:" + token);
                        String resposta = in.readLine();

                        if ("VALIDO".equals(resposta)) {
                            System.out.println("[CLIENTE] ✓ Token ainda válido");
                            validado = true;
                            break;
                        } else if ("EXPIRADO".equals(resposta)) {
                            System.out.println("[CLIENTE] ❌ Token expirado! Nova autenticação necessária");
                            return;
                        }
                    } catch (Exception e) {
                        // Tentar próxima porta
                    }
                }

                if (!validado) {
                    System.err.println("[CLIENTE] ❌ Erro na validação do token");
                    return;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}