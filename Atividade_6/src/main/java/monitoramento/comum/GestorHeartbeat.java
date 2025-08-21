package monitoramento.comum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.function.Supplier;

// CORRIGIDO: A importação de Consumer<String> foi removida, pois não é mais necessária.
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
                        if ("PONG".equals(in.readLine())) {
                            isAlvoAtivo = true;
                        }
                    } catch (Exception e) {
                        // Falha na comunicação
                    }

                    if (isAlvoAtivo) {
                        if (!noAlvo.isAtivo()) {
                            System.out.printf("[INFO] Nó %d detectou: NÓ %d RECONECTADO!%n", idSupplier.get(), idAlvo);
                        }
                        noAlvo.resetarContadorFalhas();
                        noAlvo.setAtivo(true);
                    } else {
                        noAlvo.incrementarContadorFalhas();
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
}