package monitoramento.cliente;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Cliente resiliente que realiza a autenticação e re-autenticação.
 * Ele lida com a falha do líder, tentando se autenticar novamente
 * quando o ClienteMonitor deteta a ausência de relatórios.
 */
public class ClienteAutenticado {
    private static final int PORTA_AUTENTICACAO = 9090;
    private static final String ENDERECO_LIDER = "127.0.0.1";
    private static final int MAX_TENTATIVAS_AUTH = 5;
    private static final long ATRASO_TENTATIVAS_MS = 3000; // 3 segundos

    public static void main(String[] args) {
        // Loop infinito para garantir que o cliente tente sempre se reconectar.
        while (true) {
            boolean autenticado = false;
            // Loop de tentativas de autenticação
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_AUTH; tentativa++) {
                System.out.printf("[CLIENTE] Tentando autenticar com o lider (tentativa %d de %d)...%n", tentativa, MAX_TENTATIVAS_AUTH);
                try (Socket socket = new Socket(ENDERECO_LIDER, PORTA_AUTENTICACAO);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    out.println("admin;admin");
                    String resposta = in.readLine();

                    if (resposta != null && !resposta.startsWith("ERRO")) {
                        System.out.println("[CLIENTE] Autenticacao bem-sucedida! Token recebido: " + resposta);
                        autenticado = true;
                        break; // Sai do loop de tentativas de autenticação
                    } else {
                        System.err.println("[CLIENTE] Falha na autenticacao: " + resposta);
                        Thread.sleep(ATRASO_TENTATIVAS_MS); // Espera antes de sair
                        return; // Credenciais erradas, sai do programa
                    }
                } catch (Exception e) {
                    System.err.println("[CLIENTE] Nao foi possivel conectar ao servidor de autenticacao. O lider pode estar inativo ou em processo de eleicao.");
                    if (tentativa < MAX_TENTATIVAS_AUTH) {
                        try {
                            Thread.sleep(ATRASO_TENTATIVAS_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            // Se foi autenticado, inicia o monitor.
            if (autenticado) {
                try {
                    System.out.println("\n[CLIENTE] Iniciando o cliente de monitorizacao multicast...");
                    ClienteMonitor.main(null);
                } catch (SocketTimeoutException e) {
                    // O ClienteMonitor atingiu o timeout. O líder provavelmente caiu.
                    // O loop principal (while true) irá recomeçar, forçando uma nova autenticação.
                    System.out.println("[CLIENTE] A tentar encontrar um novo lider para se autenticar...");
                }
            } else {
                System.err.println("[CLIENTE] Nao foi possivel estabelecer conexao com um lider apos " + MAX_TENTATIVAS_AUTH + " tentativas. A tentar novamente em 20 segundos.");
                try {
                    Thread.sleep(20000); // Espera um tempo maior antes de tentar todo o processo novamente.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}