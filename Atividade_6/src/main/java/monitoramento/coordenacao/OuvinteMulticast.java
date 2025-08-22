package monitoramento.coordenacao;

import java.net.*;
import java.util.function.Consumer;
import java.util.Enumeration;

public class OuvinteMulticast implements Runnable {
    private final int porta;
    private final String enderecoGrupo;
    private final Consumer<String> callbackMensagem;
    private volatile boolean ativo = true;
    private MulticastSocket socket;

    // Cache da melhor interface
    private static NetworkInterface interfaceEscolhida = null;
    private static boolean interfaceTestada = false;

    public OuvinteMulticast(int porta, String enderecoGrupo, Consumer<String> callbackMensagem) {
        this.porta = porta;
        this.enderecoGrupo = enderecoGrupo;
        this.callbackMensagem = callbackMensagem;
    }

    @Override
    public void run() {
        boolean conectado = false;

        // ESTRATÉGIA 1: Tentar MulticastSocket com interface configurada
        if (!conectado) {
            conectado = tentarMulticastComInterface();
        }

        // ESTRATÉGIA 2: Tentar MulticastSocket simples
        if (!conectado) {
            conectado = tentarMulticastSimples();
        }

        // ESTRATÉGIA 3: Fallback para UDP simples (apenas localhost)
        if (!conectado) {
            conectado = tentarUDPFallback();
        }

        if (!conectado) {
            System.err.printf("[OUVINTE] ❌ FALHA TOTAL ao conectar no grupo %s:%d%n", enderecoGrupo, porta);
            return;
        }

        // Loop principal de recepção
        escutarMensagens();
    }

    private boolean tentarMulticastComInterface() {
        try {
            socket = new MulticastSocket(porta);
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);

            // Configurar interface
            NetworkInterface ni = obterMelhorInterface();
            if (ni != null) {
                socket.setNetworkInterface(ni);
                System.out.printf("[OUVINTE] Usando interface: %s%n", ni.getDisplayName());
            }

            socket.joinGroup(grupo);
            System.out.printf("[OUVINTE] ✅ Conectado via MulticastSocket+Interface no grupo %s:%d%n",
                    enderecoGrupo, porta);
            return true;

        } catch (Exception e) {
            System.err.printf("[OUVINTE] Estratégia 1 falhou: %s%n", e.getMessage());
            fecharSocket();
            return false;
        }
    }

    private boolean tentarMulticastSimples() {
        try {
            socket = new MulticastSocket(porta);
            InetAddress grupo = InetAddress.getByName(enderecoGrupo);
            socket.joinGroup(grupo);

            System.out.printf("[OUVINTE] ✅ Conectado via MulticastSocket simples no grupo %s:%d%n",
                    enderecoGrupo, porta);
            return true;

        } catch (Exception e) {
            System.err.printf("[OUVINTE] Estratégia 2 falhou: %s%n", e.getMessage());
            fecharSocket();
            return false;
        }
    }

    private boolean tentarUDPFallback() {
        try {
            // Para UDP simples, apenas criar DatagramSocket na porta
            DatagramSocket udpSocket = new DatagramSocket(porta);

            // Converter para MulticastSocket para manter compatibilidade
            udpSocket.close();
            socket = new MulticastSocket(porta);

            System.out.printf("[OUVINTE] ✅ Conectado via UDP fallback na porta %d%n", porta);
            return true;

        } catch (Exception e) {
            System.err.printf("[OUVINTE] Estratégia 3 (UDP fallback) falhou: %s%n", e.getMessage());
            fecharSocket();
            return false;
        }
    }

    private void escutarMensagens() {
        byte[] buffer = new byte[8192]; // Buffer maior para relatórios

        System.out.printf("[OUVINTE] 👂 Escutando mensagens no grupo %s:%d...%n", enderecoGrupo, porta);

        while (ativo) {
            try {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                socket.receive(pacote);

                String mensagem = new String(pacote.getData(), 0, pacote.getLength(), "UTF-8");

                System.out.printf("[OUVINTE] 📨 Mensagem recebida (%d bytes) de %s%n",
                        pacote.getLength(), pacote.getSocketAddress());

                if (callbackMensagem != null) {
                    callbackMensagem.accept(mensagem);
                }

            } catch (SocketTimeoutException e) {
                // Timeout normal, continuar
            } catch (SocketException e) {
                if (ativo) {
                    System.err.printf("[OUVINTE] Erro no socket: %s%n", e.getMessage());
                }
                break;
            } catch (Exception e) {
                if (ativo) {
                    System.err.printf("[OUVINTE] Erro ao receber mensagem: %s%n", e.getMessage());
                }
            }
        }

        fecharSocket();
        System.out.printf("[OUVINTE] 👋 Ouvinte encerrado para %s:%d%n", enderecoGrupo, porta);
    }

    private synchronized NetworkInterface obterMelhorInterface() {
        if (interfaceTestada) {
            return interfaceEscolhida;
        }

        try {
            // Tentar loopback primeiro
            String[] nomesLoopback = {"lo", "lo0", "Loopback", "loopback"};
            for (String nome : nomesLoopback) {
                try {
                    NetworkInterface ni = NetworkInterface.getByName(nome);
                    if (ni != null && ni.supportsMulticast() && ni.isUp()) {
                        interfaceEscolhida = ni;
                        interfaceTestada = true;
                        return interfaceEscolhida;
                    }
                } catch (Exception e) {
                    // Continuar
                }
            }

            // Tentar por índice
            try {
                NetworkInterface ni = NetworkInterface.getByIndex(1);
                if (ni != null && ni.supportsMulticast() && ni.isUp() && ni.isLoopback()) {
                    interfaceEscolhida = ni;
                    interfaceTestada = true;
                    return interfaceEscolhida;
                }
            } catch (Exception e) {
                // Continuar
            }

            // Procurar qualquer interface multicast
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.supportsMulticast() && ni.isUp() && !ni.isVirtual()) {
                    if (ni.isLoopback()) {
                        interfaceEscolhida = ni;
                        break;
                    }
                    if (interfaceEscolhida == null) {
                        interfaceEscolhida = ni;
                    }
                }
            }

        } catch (Exception e) {
            System.err.printf("[OUVINTE] Erro ao detectar interface: %s%n", e.getMessage());
        }

        interfaceTestada = true;
        return interfaceEscolhida;
    }

    private void fecharSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            // Ignorar erro de fechamento
        }
    }

    public void parar() {
        this.ativo = false;
        fecharSocket();
    }
}