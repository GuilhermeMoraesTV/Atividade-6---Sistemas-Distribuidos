package monitoramento.coordenacao;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.function.Consumer;

public class OuvinteMulticast implements Runnable {
    private final int porta;
    private final String enderecoGrupo;
    private final Consumer<String> callbackMensagem;
    private volatile boolean ativo = true;
    private MulticastSocket socket;

    public OuvinteMulticast(int porta, String enderecoGrupo, Consumer<String> callbackMensagem) {
        this.porta = porta;
        this.enderecoGrupo = enderecoGrupo;
        this.callbackMensagem = callbackMensagem;
    }

    @Override
    public void run() {
        try {
            socket = new MulticastSocket(porta);
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            socket.joinGroup(grupo);

            byte[] buffer = new byte[1024];
            System.out.printf("[OUVINTE MULTICAST P%s] Escutando no grupo %s:%d...%n", Thread.currentThread().getName(), enderecoGrupo, porta);

            while (ativo) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                socket.receive(pacote);
                String mensagem = new String(pacote.getData(), 0, pacote.getLength());
                callbackMensagem.accept(mensagem);
            }
        } catch (Exception e) {
            if (ativo) {
                System.err.println("[ERRO] Erro no Ouvinte Multicast: " + e.getMessage());
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    public void parar() {
        this.ativo = false;
        if (socket != null) {
            socket.close();
        }
    }
}