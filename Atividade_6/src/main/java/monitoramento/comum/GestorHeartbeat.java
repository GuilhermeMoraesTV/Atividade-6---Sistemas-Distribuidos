package monitoramento.comum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.function.Supplier;

public class GestorHeartbeat implements Runnable {
    private final Supplier<Integer> idSupplier;
    private final Supplier<Integer> coordenadorIdSupplier;
    private final Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier;
    private final Runnable iniciarEleicaoCallback; // CORRIGIDO: O tipo agora é Runnable (uma função sem parâmetros)
    private static final int TIMEOUT_MS = 2000;

    public GestorHeartbeat(Supplier<Integer> idSupplier, Supplier<Integer> coordenadorIdSupplier, Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier, Runnable iniciarEleicaoCallback) {
        this.idSupplier = idSupplier;
        this.coordenadorIdSupplier = coordenadorIdSupplier;
        this.nosDaRedeSupplier = nosDaRedeSupplier;
        this.iniciarEleicaoCallback = iniciarEleicaoCallback;
    }

    @Override
    public void run() {
        // Aguardar inicialização completa
        try {
            Thread.sleep(10000); // 10 segundos para inicialização
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (true) {
            try {
                Thread.sleep(5000);
                Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();

                for (Map.Entry<Integer, InfoNo> entry : nosDaRede.entrySet()) {
                    int idAlvo = entry.getKey();
                    InfoNo noAlvo = entry.getValue();

                    if (idAlvo == idSupplier.get()) continue;

                    boolean isAlvoAtivo = false;
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", noAlvo.getPortaHeartbeat()), TIMEOUT_MS);
                        socket.setSoTimeout(TIMEOUT_MS);
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        out.println("PING");

                        String resposta = in.readLine();
                        if ("PONG".equals(resposta)) {
                            isAlvoAtivo = true;
                        }
                    } catch (Exception e) {
                        // Falha na comunicação - nó pode estar inativo
                    }

                    if (isAlvoAtivo) {
                        if (!noAlvo.isAtivo()) {
                            System.out.printf("[INFO] Nó %d detectou: NÓ %d RECONECTADO!%n", idSupplier.get(), idAlvo);
                        }
                        noAlvo.resetarContadorFalhas();
                        noAlvo.setAtivo(true);
                    } else {
                        noAlvo.incrementarContadorFalhas();

                        // Só considerar falho após várias tentativas consecutivas
                        if (noAlvo.getContadorFalhas() >= 3 && noAlvo.isAtivo()) {
                            System.err.printf("[FALHA] Nó %d detectou: NÓ %d CONSIDERADO FALHO!%n", idSupplier.get(), idAlvo);
                            noAlvo.setAtivo(false);

                            if (idAlvo == coordenadorIdSupplier.get()) {
                                iniciarEleicaoCallback.run();
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

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