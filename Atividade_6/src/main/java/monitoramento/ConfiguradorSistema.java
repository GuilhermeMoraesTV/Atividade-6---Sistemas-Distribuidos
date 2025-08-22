package monitoramento.comum;

import java.net.NetworkInterface;
import java.util.Enumeration;

public class ConfiguradorSistema {

    /**
     * Configura propriedades do sistema para melhor suporte a multicast
     */
    public static void configurarMulticast() {
        try {
            // Configurações para Windows/multicast
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");

            // Configurações de timeout
            System.setProperty("sun.net.useExclusiveBind", "false");

            System.out.println("[CONFIG] ✅ Propriedades de sistema configuradas para multicast");

        } catch (Exception e) {
            System.err.printf("[CONFIG] Erro ao configurar propriedades: %s%n", e.getMessage());
        }
    }

    /**
     * Diagnóstico de rede para multicast
     */
    public static void diagnosticarRede() {
        System.out.println("\n[DIAGNÓSTICO] 🔍 Verificando configuração de rede...");

        try {
            System.out.printf("[DIAGNÓSTICO] OS: %s %s%n",
                    System.getProperty("os.name"), System.getProperty("os.version"));
            System.out.printf("[DIAGNÓSTICO] Java: %s%n", System.getProperty("java.version"));

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int count = 0;

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (ni.supportsMulticast() && ni.isUp()) {
                    count++;
                    System.out.printf("[DIAGNÓSTICO] ✅ Interface multicast: %s (%s)%n",
                            ni.getName(), ni.getDisplayName());

                    if (ni.isLoopback()) {
                        System.out.printf("  └─ 🔄 LOOPBACK (ideal para testes locais)%n");
                    }

                    // Mostrar IPs
                    ni.getInetAddresses().asIterator().forEachRemaining(addr -> {
                        System.out.printf("  └─ IP: %s%n", addr.getHostAddress());
                    });
                }
            }

            if (count == 0) {
                System.err.println("[DIAGNÓSTICO] ❌ ERRO: Nenhuma interface multicast disponível!");
            } else {
                System.out.printf("[DIAGNÓSTICO] ✅ %d interface(s) multicast disponível(eis)%n", count);
            }

            // Testar endereços multicast
            try {
                java.net.InetAddress multicastAddr = java.net.InetAddress.getByName("239.0.0.1");
                System.out.printf("[DIAGNÓSTICO] ✅ Endereço multicast resolvido: %s%n", multicastAddr);
                System.out.printf("[DIAGNÓSTICO] ✅ Endereço é válido para multicast: %s%n",
                        multicastAddr.isMulticastAddress());
            } catch (Exception e) {
                System.err.printf("[DIAGNÓSTICO] ❌ Erro ao resolver endereço multicast: %s%n", e.getMessage());
            }

            // Testar criação de socket
            try (java.net.DatagramSocket testSocket = new java.net.DatagramSocket()) {
                System.out.println("[DIAGNÓSTICO] ✅ Pode criar DatagramSocket");
            } catch (Exception e) {
                System.err.printf("[DIAGNÓSTICO] ❌ Erro ao criar DatagramSocket: %s%n", e.getMessage());
            }

            System.out.println("[DIAGNÓSTICO] ✅ Diagnóstico completo.\n");

        } catch (Exception e) {
            System.err.printf("[DIAGNÓSTICO] Erro geral no diagnóstico: %s%n", e.getMessage());
        }
    }

    /**
     * Inicialização completa do sistema
     */
    public static void inicializar() {
        configurarMulticast();
        diagnosticarRede();
    }
}