// Ficheiro: src/monitoramento/coordenacao/EmissorMulticast.java
package monitoramento.coordenacao;

import monitoramento.comum.Recurso;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.List;

public class EmissorMulticast {
    private static final String ENDERECO_CLIENTES = "239.0.0.1";
    private static final int PORTA_CLIENTES = 12345;

    // Método para enviar uma mensagem de texto simples para um grupo específico
    public void enviarMensagem(String mensagem, String enderecoGrupo, int porta) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] dados = mensagem.getBytes();
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, porta);
            socket.send(pacote);
        } catch (Exception e) {
            System.err.println("[EMISSOR] Erro ao enviar mensagem multicast: " + e.getMessage());
        }
    }

    // O seu método antigo para enviar relatórios de estado (sem alterações)
    public void enviarRelatorio(int idLider, List<Recurso> snapshot) {
        // ... (todo o código do seu método 'enviar' antigo, que formata a tabela, etc.)
        // A única mudança é renomear o método para 'enviarRelatorio' para clareza.
        // E usar as constantes ENDERECO_CLIENTES e PORTA_CLIENTES.
    }
}