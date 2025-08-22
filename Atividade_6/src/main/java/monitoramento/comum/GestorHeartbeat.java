package monitoramento.comum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Classe responsável por gerenciar o mecanismo de Heartbeat entre os nós da rede.
 * O objetivo é verificar periodicamente se os outros nós estão ativos,
 * detectando falhas e acionando uma eleição caso o coordenador fique inativo.
 */
public class GestorHeartbeat implements Runnable {
    // Fornece dinamicamente o ID do nó atual
    private final Supplier<Integer> idSupplier;

    // Fornece dinamicamente o ID do nó que atualmente é o coordenador
    private final Supplier<Integer> coordenadorIdSupplier;

    // Fornece dinamicamente o mapa de nós da rede (id -> InfoNo)
    private final Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier;

    // Callback chamado quando é necessário iniciar um processo de eleição
    private final Runnable iniciarEleicaoCallback;

    // Tempo máximo de espera em milissegundos para resposta de um nó
    private static final int TIMEOUT_MS = 7000;

    /**
     * Construtor recebe funções para acessar informações dinâmicas da rede.
     */
    public GestorHeartbeat(
            Supplier<Integer> idSupplier,
            Supplier<Integer> coordenadorIdSupplier,
            Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier,
            Runnable iniciarEleicaoCallback
    ) {
        this.idSupplier = idSupplier;
        this.coordenadorIdSupplier = coordenadorIdSupplier;
        this.nosDaRedeSupplier = nosDaRedeSupplier;
        this.iniciarEleicaoCallback = iniciarEleicaoCallback;
    }

    @Override
    public void run() {
        // Aguarda 10 segundos antes de começar (tempo para inicialização do sistema)
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // Interrompe a execução se a thread for interrompida
        }

        // Loop infinito de monitoramento
        while (true) {
            try {
                // Executa a cada 5 segundos
                Thread.sleep(5000);

                // Obtém todos os nós da rede
                Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();

                // Percorre cada nó registrado
                for (Map.Entry<Integer, InfoNo> entry : nosDaRede.entrySet()) {
                    int idAlvo = entry.getKey();
                    InfoNo noAlvo = entry.getValue();

                    // Não testa o próprio nó
                    if (idAlvo == idSupplier.get()) continue;

                    boolean isAlvoAtivo = false;

                    // Testa conexão com o nó alvo
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", noAlvo.getPortaHeartbeat()), TIMEOUT_MS);
                        socket.setSoTimeout(TIMEOUT_MS);

                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                        // Envia um "PING" e espera resposta
                        out.println("PING");
                        String resposta = in.readLine();

                        // Se respondeu "PONG", consideramos ativo
                        if ("PONG".equals(resposta)) {
                            isAlvoAtivo = true;
                        }
                    } catch (Exception e) {
                        // Se não foi possível conectar/responder, nó pode estar inativo
                    }

                    if (isAlvoAtivo) {
                        // Caso nó volte a ficar ativo após falha
                        if (!noAlvo.isAtivo()) {
                            System.out.printf("[INFO] Nó %d detectou: NÓ %d RECONECTADO!%n", idSupplier.get(), idAlvo);
                        }

                        // Reseta contador de falhas e marca como ativo
                        noAlvo.resetarContadorFalhas();
                        noAlvo.setAtivo(true);
                    } else {
                        // Incrementa falhas consecutivas do nó
                        noAlvo.incrementarContadorFalhas();

                        // Só considera o nó como falho após 3 tentativas consecutivas
                        if (noAlvo.getContadorFalhas() >= 3 && noAlvo.isAtivo()) {
                            System.err.printf("[FALHA] Nó %d detectou: NÓ %d CONSIDERADO FALHO!%n", idSupplier.get(), idAlvo);
                            noAlvo.setAtivo(false);

                            // Se o nó falho for o coordenador, inicia eleição
                            if (idAlvo == coordenadorIdSupplier.get()) {
                                iniciarEleicaoCallback.run();
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Se a thread for interrompida, finaliza o loop
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Método auxiliar que tenta conectar em um nó e verificar se responde ao "PING".
     * @param idAlvo ID do nó alvo
     * @param noAlvo Informações do nó alvo
     * @return true se respondeu corretamente, false caso contrário
     */
    private boolean testarConexaoNo(int idAlvo, InfoNo noAlvo) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", noAlvo.getPortaHeartbeat()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println("PING");
            String resposta = in.readLine();

            return "PONG".equals(resposta);
        } catch (Exception e) {
            return false;
        }
    }
}
