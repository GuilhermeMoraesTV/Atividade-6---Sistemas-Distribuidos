// Ficheiro: src/monitoramento/coordenacao/EmissorMulticast.java
package monitoramento.coordenacao;

import monitoramento.comum.Recurso;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class EmissorMulticast {
    private static final String ENDERECO_CLIENTES = "239.0.0.1";
    private static final int PORTA_CLIENTES = 12345;

    // Método para enviar uma mensagem de texto simples para um grupo específico
    public void enviarMensagem(String mensagem, String enderecoGrupo, int porta) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] dados = mensagem.getBytes("UTF-8");
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, porta);
            socket.send(pacote);
        } catch (Exception e) {
            System.err.println("[EMISSOR] Erro ao enviar mensagem multicast: " + e.getMessage());
        }
    }

    // Método para enviar relatórios de estado formatados para clientes
    public void enviarRelatorio(int idLider, List<Recurso> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        StringBuilder relatorio = new StringBuilder();

        // Cabeçalho do relatório
        relatorio.append("\n");
        relatorio.append("=".repeat(80)).append("\n");
        relatorio.append("           RELATÓRIO DE MONITORAMENTO DO SISTEMA DISTRIBUÍDO\n");
        relatorio.append("=".repeat(80)).append("\n");

        // Informações do líder e timestamp
        LocalDateTime agora = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        relatorio.append(String.format("Líder Atual: P%-3d | Data/Hora: %s | Nós Ativos: %d\n",
                idLider, agora.format(formatter), snapshot.size()));
        relatorio.append("-".repeat(80)).append("\n");

        // Cabeçalho da tabela
        relatorio.append("| NÓ   | CPU          | MEMÓRIA          | CARGA SYS   | PROCESSADORES | UPTIME       |\n");
        relatorio.append("|------|--------------|------------------|-------------|---------------|---------------|\n");

        // Dados dos nós
        for (Recurso recurso : snapshot) {
            relatorio.append(recurso.paraLinhaRelatorio()).append(" |\n");
        }

        relatorio.append("-".repeat(80)).append("\n");

        // Estatísticas resumidas
        double cpuMedia = snapshot.stream().mapToDouble(Recurso::getUsoCpu).average().orElse(0.0);
        double memoriaMedia = snapshot.stream().mapToDouble(Recurso::getUsoMemoria).average().orElse(0.0);
        int totalProcessadores = snapshot.stream().mapToInt(Recurso::getProcessadores).sum();

        relatorio.append(String.format("CPU Média: %.2f%% | Memória Média: %.2f%% | Total Processadores: %d\n",
                cpuMedia, memoriaMedia, totalProcessadores));
        relatorio.append("=".repeat(80)).append("\n");

        // Enviar o relatório via multicast
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] dados = relatorio.toString().getBytes("UTF-8");
            InetAddress grupo = InetAddress.getByName(ENDERECO_CLIENTES);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, PORTA_CLIENTES);
            socket.send(pacote);

            System.out.printf("[EMISSOR] Líder P%d enviou relatório via multicast (%d bytes)%n",
                    idLider, dados.length);
        } catch (Exception e) {
            System.err.printf("[EMISSOR] Erro ao enviar relatório do líder P%d: %s%n", idLider, e.getMessage());
        }
    }

    // Método para enviar notificações de eventos importantes
    public void enviarNotificacao(String evento, int idNo) {
        String mensagem = String.format("\n[NOTIFICAÇÃO] %s - Nó P%d - %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                idNo, evento);
        enviarMensagem(mensagem, ENDERECO_CLIENTES, PORTA_CLIENTES);
    }
}