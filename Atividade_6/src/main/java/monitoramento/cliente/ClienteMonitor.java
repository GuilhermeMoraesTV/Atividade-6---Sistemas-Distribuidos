package monitoramento.cliente;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException; // Importar a exceção

/**
 * Cliente que escuta os relatórios de estado da rede.
 * AGORA COM LÓGICA DE TIMEOUT para detectar falhas do líder.
 */
public class ClienteMonitor {
    private static final String ENDERECO_MULTICAST = "239.0.0.1";
    private static final int PORTA_MULTICAST = 12345;
    // Timeout de 25 segundos. Se nenhum relatório chegar neste tempo, assume-se que o líder falhou.
    private static final int TIMEOUT_RECEPCAO_MS = 25000;

    // O método main agora pode lançar uma exceção para ser apanhada pelo ClienteAutenticado
    public static void main(String[] args) throws SocketTimeoutException {
        try (MulticastSocket socket = new MulticastSocket(PORTA_MULTICAST)) {
            InetAddress grupo = InetAddress.getByName(ENDERECO_MULTICAST);

            InetAddress localHost = InetAddress.getByName("127.0.0.1");
            socket.setNetworkInterface(NetworkInterface.getByInetAddress(localHost));
            socket.joinGroup(grupo);

            // Define o timeout no socket.
            socket.setSoTimeout(TIMEOUT_RECEPCAO_MS);

            System.out.println("==========================================================");
            System.out.println("Cliente de Monitorizacao iniciado. Aguardando relatorios...");
            System.out.println("==========================================================");

            byte[] buffer = new byte[4096];
            while (true) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                // Esta chamada agora irá bloquear por no máximo 25 segundos.
                socket.receive(pacote);

                String relatorio = new String(pacote.getData(), 0, pacote.getLength(), "UTF-8");
                System.out.println(relatorio);
            }

        } catch (SocketTimeoutException e) {
            // Se o timeout for atingido, lança a exceção para cima.
            // O ClienteAutenticado irá apanhar isto e saber que precisa de se re-autenticar.
            System.err.printf("%n[AVISO] Nenhum relatorio recebido em %d segundos. O lider pode ter mudado.%n", TIMEOUT_RECEPCAO_MS / 1000);
            throw e;
        } catch (Exception e) {
            System.err.println("Erro no Cliente de Monitorizacao: " + e.getMessage());
        }
    }
}