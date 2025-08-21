package monitoramento.cliente;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Cliente resiliente que realiza a autenticação e re-autenticação.
 * Versão corrigida com protocolo adequado para o ServidorAutenticacao.
 */
public class ClienteAutenticado {
    private static final int PORTA_AUTENTICACAO = 9090;
    private static final String ENDERECO_LIDER = "127.0.0.1";
    private static final int MAX_TENTATIVAS_AUTH = 5;
    private static final long ATRASO_TENTATIVAS_MS = 3000; // 3 segundos

    public static void main(String[] args) {
        System.out.println("[CLIENTE] Sistema de Monitoramento - Cliente Autenticado");
        System.out.println("[CLIENTE] Iniciando processo de autenticação...");

        // Loop infinito para garantir que o cliente tente sempre se reconectar.
        while (true) {
            boolean autenticado = false;
            String tokenAtual = null;

            // Loop de tentativas de autenticação
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_AUTH; tentativa++) {
                System.out.printf("[CLIENTE] Tentando autenticar com o líder (tentativa %d de %d)...%n",
                        tentativa, MAX_TENTATIVAS_AUTH);

                try (Socket socket = new Socket(ENDERECO_LIDER, PORTA_AUTENTICACAO);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    // PROTOCOLO CORRETO: "LOGIN:admin;admin"
                    out.println("LOGIN:admin;admin");
                    String resposta = in.readLine();

                    if (resposta != null && resposta.startsWith("TOKEN:")) {
                        tokenAtual = resposta.substring(6); // Remove "TOKEN:"
                        System.out.println("[CLIENTE] Autenticação bem-sucedida! Token recebido: " + tokenAtual);
                        autenticado = true;
                        break; // Sai do loop de tentativas de autenticação
                    } else {
                        System.err.println("[CLIENTE] Falha na autenticação: " + resposta);
                        if (resposta != null && resposta.contains("Comando ou credenciais invalidas")) {
                            System.err.println("[CLIENTE] Credenciais incorretas. Encerrando...");
                            return; // Credenciais erradas, sai do programa
                        }
                        Thread.sleep(ATRASO_TENTATIVAS_MS);
                    }
                } catch (Exception e) {
                    System.err.printf("[CLIENTE] Não foi possível conectar ao servidor de autenticação: %s%n",
                            e.getMessage());
                    System.err.println("[CLIENTE] O líder pode estar inativo ou em processo de eleição.");
                    if (tentativa < MAX_TENTATIVAS_AUTH) {
                        try {
                            Thread.sleep(ATRASO_TENTATIVAS_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            // Se foi autenticado, inicia o monitor e valida token periodicamente
            if (autenticado && tokenAtual != null) {
                try {
                    System.out.println("\n[CLIENTE] Iniciando cliente de monitorização multicast...");

                    // Iniciar thread de validação periódica do token
                    String finalTokenAtual = tokenAtual;
                    Thread threadValidacao = new Thread(() -> validarTokenPeriodicamente(finalTokenAtual));
                    threadValidacao.setDaemon(true);
                    threadValidacao.start();

                    // Iniciar o monitor (pode lançar SocketTimeoutException)
                    ClienteMonitor.main(null);

                } catch (SocketTimeoutException e) {
                    // O ClienteMonitor atingiu o timeout. O líder provavelmente caiu.
                    System.out.println("[CLIENTE] Timeout no monitoramento. Tentando encontrar novo líder...");
                } catch (Exception e) {
                    System.err.println("[CLIENTE] Erro inesperado no monitoramento: " + e.getMessage());
                }
            } else {
                System.err.printf("[CLIENTE] Não foi possível estabelecer conexão com um líder após %d tentativas.%n",
                        MAX_TENTATIVAS_AUTH);
                System.out.println("[CLIENTE] Tentando novamente em 20 segundos...");
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Valida o token periodicamente para detectar expiração
     */
    private static void validarTokenPeriodicamente(String token) {
        while (true) {
            try {
                Thread.sleep(60000); // Validar a cada 1 minuto

                try (Socket socket = new Socket(ENDERECO_LIDER, PORTA_AUTENTICACAO);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    // PROTOCOLO CORRETO: "VALIDATE:token"
                    out.println("VALIDATE:" + token);
                    String resposta = in.readLine();

                    if ("VALIDO".equals(resposta)) {
                        System.out.println("[CLIENTE] Token ainda válido.");
                    } else if ("EXPIRADO".equals(resposta)) {
                        System.out.println("[CLIENTE] Token expirado! Necessária nova autenticação.");
                        return; // Sai da thread, forçando nova autenticação
                    } else {
                        System.err.println("[CLIENTE] Resposta inesperada na validação: " + resposta);
                    }
                } catch (Exception e) {
                    System.err.println("[CLIENTE] Erro na validação do token: " + e.getMessage());
                    return; // Sai da thread se não conseguir validar
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}