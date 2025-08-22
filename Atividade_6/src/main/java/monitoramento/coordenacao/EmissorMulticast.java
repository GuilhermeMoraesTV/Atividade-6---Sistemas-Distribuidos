package monitoramento.coordenacao;

import monitoramento.comum.Recurso;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Enumeration;

/**
 * Classe responsável por emitir mensagens multicast no sistema distribuído.
 * O EmissorMulticast envia relatórios e notificações para os clientes,
 * utilizando diferentes estratégias de fallback caso o envio via multicast falhe.
 */
public class EmissorMulticast {
    // Endereço multicast dos clientes
    private static final String ENDERECO_CLIENTES = "239.0.0.1";
    // Porta utilizada para comunicação multicast
    private static final int PORTA_CLIENTES = 12345;

    // Cache da melhor interface encontrada para multicast
    private static NetworkInterface interfaceMulticast = null;
    private static boolean interfaceTestada = false;

    /**
     * Detecta e configura a melhor interface de rede para envio de mensagens multicast.
     * Dá preferência para interfaces de loopback (para testes locais).
     */
    private synchronized NetworkInterface obterInterfaceMulticast() {
        if (interfaceTestada) {
            return interfaceMulticast;
        }

        try {
            // Priorizar nomes comuns de interfaces loopback
            String[] nomesPreferidos = {"lo", "lo0", "Loopback", "loopback"};

            // 1. Tentar encontrar loopback pelo nome
            for (String nome : nomesPreferidos) {
                try {
                    NetworkInterface ni = NetworkInterface.getByName(nome);
                    if (ni != null && ni.supportsMulticast() && ni.isUp()) {
                        System.out.printf("[EMISSOR] Interface loopback encontrada: %s%n", ni.getDisplayName());
                        interfaceMulticast = ni;
                        interfaceTestada = true;
                        return interfaceMulticast;
                    }
                } catch (Exception e) {
                    // Ignora e tenta a próxima
                }
            }

            // 2. Tentar encontrar loopback pelo índice (geralmente 1)
            try {
                NetworkInterface ni = NetworkInterface.getByIndex(1);
                if (ni != null && ni.supportsMulticast() && ni.isUp() && ni.isLoopback()) {
                    System.out.printf("[EMISSOR] Interface loopback por índice: %s%n", ni.getDisplayName());
                    interfaceMulticast = ni;
                    interfaceTestada = true;
                    return interfaceMulticast;
                }
            } catch (Exception e) {
                // Continua tentando
            }

            // 3. Procurar qualquer interface que suporte multicast
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.supportsMulticast() && ni.isUp() && !ni.isVirtual()) {
                    // Preferir loopback se disponível
                    if (ni.isLoopback()) {
                        System.out.printf("[EMISSOR] Interface loopback encontrada: %s%n", ni.getDisplayName());
                        interfaceMulticast = ni;
                        interfaceTestada = true;
                        return interfaceMulticast;
                    }
                    // Caso contrário, guardar a primeira válida encontrada
                    if (interfaceMulticast == null) {
                        interfaceMulticast = ni;
                    }
                }
            }

            // Se encontrou alguma interface válida, usa-a
            if (interfaceMulticast != null) {
                System.out.printf("[EMISSOR] Interface multicast selecionada: %s%n", interfaceMulticast.getDisplayName());
            } else {
                System.err.println("[EMISSOR] AVISO: Nenhuma interface multicast encontrada");
            }

        } catch (Exception e) {
            System.err.printf("[EMISSOR] Erro ao detectar interface multicast: %s%n", e.getMessage());
        }

        interfaceTestada = true;
        return interfaceMulticast;
    }

    /**
     * Envia uma mensagem multicast utilizando múltiplas estratégias de fallback.
     * Estratégias:
     * 1. Multicast configurando explicitamente a interface
     * 2. Multicast simples
     * 3. UDP local (fallback garantido)
     */
    public void enviarMensagem(String mensagem, String enderecoGrupo, int porta) {
        boolean sucesso = false;

        // Estratégia 1
        sucesso = tentarMulticastComInterface(mensagem, enderecoGrupo, porta);

        if (!sucesso) {
            // Estratégia 2
            sucesso = tentarMulticastSimples(mensagem, enderecoGrupo, porta);
        }

        if (!sucesso) {
            // Estratégia 3
            sucesso = tentarUDPFallback(mensagem, porta);
        }

        if (!sucesso) {
            System.err.printf("[EMISSOR]  FALHA TOTAL ao enviar mensagem para %s:%d%n", enderecoGrupo, porta);
        }
    }

    /**
     * Estratégia 1: Enviar multicast configurando explicitamente a interface de rede.
     */
    private boolean tentarMulticastComInterface(String mensagem, String enderecoGrupo, int porta) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setLoopbackMode(false); // Permite que o próprio emissor receba a mensagem
            socket.setTimeToLive(1);       // Restringe ao escopo local

            // Configurar interface detectada
            NetworkInterface ni = obterInterfaceMulticast();
            if (ni != null) {
                socket.setNetworkInterface(ni);
            }

            byte[] dados = mensagem.getBytes("UTF-8");
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, porta);

            socket.send(pacote);

            System.out.printf("[EMISSOR]  Multicast enviado para %s:%d (%d bytes)%n",
                    enderecoGrupo, porta, dados.length);
            return true;

        } catch (Exception e) {
            System.err.printf("[EMISSOR] Estratégia 1 (MulticastSocket+Interface) falhou: %s%n", e.getMessage());
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Estratégia 2: Enviar multicast sem configurar interface específica.
     */
    private boolean tentarMulticastSimples(String mensagem, String enderecoGrupo, int porta) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setLoopbackMode(false);
            socket.setTimeToLive(1);

            byte[] dados = mensagem.getBytes("UTF-8");
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, porta);

            socket.send(pacote);

            System.out.printf("[EMISSOR]  Multicast simples enviado para %s:%d (%d bytes)%n",
                    enderecoGrupo, porta, dados.length);
            return true;

        } catch (Exception e) {
            System.err.printf("[EMISSOR] Estratégia 2 (MulticastSocket simples) falhou: %s%n", e.getMessage());
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Estratégia 3: Enviar via UDP diretamente para localhost (funciona mesmo sem suporte multicast).
     */
    private boolean tentarUDPFallback(String mensagem, int porta) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            byte[] dados = mensagem.getBytes("UTF-8");
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, localhost, porta);

            socket.send(pacote);

            System.out.printf("[EMISSOR]  UDP fallback enviado para 127.0.0.1:%d (%d bytes)%n",
                    porta, dados.length);
            return true;

        } catch (Exception e) {
            System.err.printf("[EMISSOR] Estratégia 3 (UDP fallback) falhou: %s%n", e.getMessage());
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Envia um relatório consolidado do estado dos recursos monitorados.
     */
    public void enviarRelatorio(int idLider, List<Recurso> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            System.err.printf("[EMISSOR] Snapshot vazio para líder P%d%n", idLider);
            return;
        }

        System.out.printf("[EMISSOR]  Preparando relatório do líder P%d com %d recursos%n",
                idLider, snapshot.size());

        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n");
        relatorio.append("=".repeat(80)).append("\n");
        relatorio.append("           RELATÓRIO DE MONITORAMENTO DO SISTEMA DISTRIBUÍDO\n");
        relatorio.append("=".repeat(80)).append("\n");

        // Cabeçalho com data e líder atual
        LocalDateTime agora = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        relatorio.append(String.format("Líder Atual: P%-3d | Data/Hora: %s | Nós Ativos: %d\n",
                idLider, agora.format(formatter), snapshot.size()));
        relatorio.append("-".repeat(80)).append("\n");

        // Tabela com os dados dos nós
        relatorio.append("| NÓ   | CPU          | MEMÓRIA          | CARGA SYS   | PROCESSADORES | UPTIME       |\n");
        relatorio.append("|------|--------------|------------------|-------------|---------------|---------------|\n");

        for (Recurso recurso : snapshot) {
            relatorio.append(recurso.paraLinhaRelatorio()).append(" |\n");
        }

        relatorio.append("-".repeat(80)).append("\n");

        // Estatísticas agregadas
        double cpuMedia = snapshot.stream().mapToDouble(Recurso::getUsoCpu).average().orElse(0.0);
        double memoriaMedia = snapshot.stream().mapToDouble(Recurso::getUsoMemoria).average().orElse(0.0);
        int totalProcessadores = snapshot.stream().mapToInt(Recurso::getProcessadores).sum();

        relatorio.append(String.format("CPU Média: %.2f%% | Memória Média: %.2f%% | Total Processadores: %d\n",
                cpuMedia, memoriaMedia, totalProcessadores));
        relatorio.append("=".repeat(80)).append("\n");

        // Envia o relatório utilizando as estratégias de fallback
        enviarMensagem(relatorio.toString(), ENDERECO_CLIENTES, PORTA_CLIENTES);

        System.out.printf("[EMISSOR]  RELATÓRIO P%d PROCESSADO%n", idLider);
    }

    /**
     * Envia uma notificação de evento para os clientes.
     */
    public void enviarNotificacao(String evento, int idNo) {
        String mensagem = String.format("\n[NOTIFICAÇÃO] %s - Nó P%d - %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                idNo, evento);
        enviarMensagem(mensagem, ENDERECO_CLIENTES, PORTA_CLIENTES);
    }
}
