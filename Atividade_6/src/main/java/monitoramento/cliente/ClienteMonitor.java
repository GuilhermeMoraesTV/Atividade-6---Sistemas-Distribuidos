package monitoramento.cliente;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClienteMonitor {
    private static final String ENDERECO_MULTICAST = "239.0.0.1";
    private static final int PORTA_MULTICAST = 12345;
    private static final int TIMEOUT_RECEPCAO_MS = 25000;

    public static void main(String[] args) throws SocketTimeoutException {
        System.out.println("==========================================================");
        System.out.println("Cliente de Monitorização iniciado. Configurando multicast...");
        System.out.println("==========================================================");

        diagnosticarRede();

        // Tentar múltiplas estratégias de conexão
        SocketTimeoutException ultimaExcecao = null;

        // Estratégia 1: MulticastSocket padrão
        try {
            System.out.println("[CLIENTE] 🔄 ESTRATÉGIA 1: MulticastSocket padrão");
            conectarMulticastPadrao();
            return; // Se chegou aqui, funcionou
        } catch (SocketTimeoutException e) {
            System.err.println("[CLIENTE] ❌ Estratégia 1 falhou: timeout");
            ultimaExcecao = e;
        } catch (Exception e) {
            System.err.printf("[CLIENTE] ❌ Estratégia 1 falhou: %s%n", e.getMessage());
        }

        // Estratégia 2: MulticastSocket com interface específica
        try {
            System.out.println("[CLIENTE] 🔄 ESTRATÉGIA 2: MulticastSocket com loopback");
            conectarMulticastComLoopback();
            return;
        } catch (SocketTimeoutException e) {
            System.err.println("[CLIENTE] ❌ Estratégia 2 falhou: timeout");
            ultimaExcecao = e;
        } catch (Exception e) {
            System.err.printf("[CLIENTE] ❌ Estratégia 2 falhou: %s%n", e.getMessage());
        }

        // Estratégia 3: DatagramSocket UDP direto (fallback)
        try {
            System.out.println("[CLIENTE] 🔄 ESTRATÉGIA 3: UDP direto para localhost");
            conectarUdpDireto();
            return;
        } catch (SocketTimeoutException e) {
            System.err.println("[CLIENTE] ❌ Estratégia 3 falhou: timeout");
            ultimaExcecao = e;
        } catch (Exception e) {
            System.err.printf("[CLIENTE] ❌ Estratégia 3 falhou: %s%n", e.getMessage());
        }

        // Se todas falharam, lançar a última exceção de timeout
        if (ultimaExcecao != null) {
            throw ultimaExcecao;
        } else {
            throw new RuntimeException("Todas as estratégias de conexão falharam");
        }
    }

    private static void conectarMulticastPadrao() throws Exception {
        try (MulticastSocket socket = new MulticastSocket(PORTA_MULTICAST)) {
            InetAddress grupo = InetAddress.getByName(ENDERECO_MULTICAST);

            socket.setLoopbackMode(false); // CRÍTICO para Windows
            socket.joinGroup(grupo);
            socket.setSoTimeout(TIMEOUT_RECEPCAO_MS);

            System.out.println("[CLIENTE] ✓ Conectado via MulticastSocket padrão");
            System.out.printf("[CLIENTE] ✓ LoopbackMode: %s%n", socket.getLoopbackMode());

            receberPacotes(socket, "MULTICAST PADRÃO");
        }
    }

    private static void conectarMulticastComLoopback() throws Exception {
        try (MulticastSocket socket = new MulticastSocket(PORTA_MULTICAST)) {
            InetAddress grupo = InetAddress.getByName(ENDERECO_MULTICAST);

            // Configurar explicitamente para loopback
            socket.setLoopbackMode(false);

            NetworkInterface loopback = NetworkInterface.getByName("lo");
            if (loopback == null) {
                loopback = NetworkInterface.getByIndex(1); // Windows loopback
            }

            if (loopback != null) {
                socket.setNetworkInterface(loopback);
                socket.joinGroup(new InetSocketAddress(grupo, PORTA_MULTICAST), loopback);
                System.out.printf("[CLIENTE] ✓ Usando interface: %s%n", loopback.getDisplayName());
            } else {
                socket.joinGroup(grupo);
            }

            socket.setSoTimeout(TIMEOUT_RECEPCAO_MS);
            System.out.println("[CLIENTE] ✓ Conectado via MulticastSocket com loopback");

            receberPacotes(socket, "MULTICAST LOOPBACK");
        }
    }

    private static void conectarUdpDireto() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(PORTA_MULTICAST)) {
            socket.setSoTimeout(TIMEOUT_RECEPCAO_MS);

            System.out.println("[CLIENTE] ✓ Conectado via UDP direto");
            System.out.println("[CLIENTE] ⚠️  MODO FALLBACK: Escutando apenas localhost");

            receberPacotes(socket, "UDP DIRETO");
        }
    }

    private static void receberPacotes(DatagramSocket socket, String modo) throws IOException {
        System.out.printf("[CLIENTE] 🎧 [%s] Aguardando relatórios...%n", modo);

        byte[] buffer = new byte[8192];
        int contadorPacotes = 0;

        // Mostrar progresso a cada 5 segundos
        ScheduledExecutorService progressTimer = Executors.newSingleThreadScheduledExecutor();
        int finalContadorPacotes = contadorPacotes;
        progressTimer.scheduleAtFixedRate(() -> {
            System.out.printf("[CLIENTE] ⏳ [%s] Ainda aguardando... (timeout em %ds)%n",
                    modo, (TIMEOUT_RECEPCAO_MS - (finalContadorPacotes * 5000)) / 1000);
        }, 5, 5, TimeUnit.SECONDS);

        try {
            while (true) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(pacote);
                    contadorPacotes++;

                    progressTimer.shutdown(); // Parar timer de progresso

                    String relatorio = new String(pacote.getData(), 0, pacote.getLength(), "UTF-8");

                    System.out.printf("\n🎉 [%s] RELATÓRIO #%d RECEBIDO! 🎉%n", modo, contadorPacotes);
                    System.out.printf("[CLIENTE] 📊 %d bytes de %s:%d%n",
                            pacote.getLength(),
                            pacote.getAddress().getHostAddress(),
                            pacote.getPort());
                    System.out.println(relatorio);
                    System.out.printf("\n[CLIENTE] 🎧 [%s] Aguardando próximo relatório...%n", modo);

                    // Reiniciar timer para próximo pacote
                    if (!progressTimer.isShutdown()) {
                        progressTimer = Executors.newSingleThreadScheduledExecutor();
                        progressTimer.scheduleAtFixedRate(() -> {
                            System.out.printf("[CLIENTE] ⏳ [%s] Aguardando próximo...%n", modo);
                        }, 30, 30, TimeUnit.SECONDS);
                    }

                } catch (IOException e) {
                    progressTimer.shutdown();
                    System.err.printf("%n⏰ [%s] TIMEOUT após %d segundos%n", modo, TIMEOUT_RECEPCAO_MS / 1000);
                    System.err.printf("[CLIENTE] 📊 Total de relatórios recebidos: %d%n", contadorPacotes);
                    throw e;
                }
            }
        } finally {
            progressTimer.shutdown();
        }
    }

    private static void diagnosticarRede() {
        System.out.println("[DIAGNÓSTICO] 🔍 Verificando configuração de rede...");

        try {
            // Informações do sistema
            System.out.printf("[DIAGNÓSTICO] OS: %s %s%n",
                    System.getProperty("os.name"),
                    System.getProperty("os.version"));
            System.out.printf("[DIAGNÓSTICO] Java: %s%n", System.getProperty("java.version"));

            // Verificar suporte a multicast
            boolean multicastSupported = false;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && ni.supportsMulticast()) {
                    multicastSupported = true;
                    System.out.printf("[DIAGNÓSTICO] 🌐 Interface multicast: %s (%s)%n",
                            ni.getName(), ni.getDisplayName());

                    // Mostrar endereços IP
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        System.out.printf("[DIAGNÓSTICO]   └─ IP: %s%n", addr.getHostAddress());
                    }
                }
            }

            if (!multicastSupported) {
                System.err.println("[DIAGNÓSTICO] ⚠️  AVISO: Nenhuma interface com suporte a multicast encontrada!");
            }

            // Teste de conectividade básica
            try {
                InetAddress multicastAddr = InetAddress.getByName(ENDERECO_MULTICAST);
                System.out.printf("[DIAGNÓSTICO] ✓ Endereço multicast resolvido: %s%n", multicastAddr);

                if (multicastAddr.isMulticastAddress()) {
                    System.out.println("[DIAGNÓSTICO] ✓ Endereço é válido para multicast");
                } else {
                    System.err.println("[DIAGNÓSTICO] ❌ Endereço NÃO é multicast!");
                }
            } catch (Exception e) {
                System.err.printf("[DIAGNÓSTICO] ❌ Erro ao resolver endereço: %s%n", e.getMessage());
            }

            // Teste de porta
            try (DatagramSocket testSocket = new DatagramSocket()) {
                System.out.println("[DIAGNÓSTICO] ✓ Pode criar DatagramSocket");
            } catch (Exception e) {
                System.err.printf("[DIAGNÓSTICO] ❌ Erro ao criar socket: %s%n", e.getMessage());
            }

        } catch (Exception e) {
            System.err.printf("[DIAGNÓSTICO] ❌ Erro geral no diagnóstico: %s%n", e.getMessage());
        }

        System.out.println("[DIAGNÓSTICO] 🔍 Diagnóstico completo.\n");
    }
}