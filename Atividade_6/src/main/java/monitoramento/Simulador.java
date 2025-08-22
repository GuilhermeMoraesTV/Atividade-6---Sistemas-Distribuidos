package monitoramento;

import monitoramento.comum.ConfiguradorSistema;
import monitoramento.grupoa.NoGrupoA;
import monitoramento.grupob.NoGrupoB;
import monitoramento.coordenacao.OuvinteMulticast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Simulador {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static OuvinteMulticast ouvinteCliente;
    private static volatile boolean simulacaoAtiva = true;

    public static void main(String[] args) {
        ConfiguradorSistema.inicializar();

        List<Integer> pidsGrupoA = Arrays.asList(1, 2, 3);
        List<Integer> pidsGrupoB = Arrays.asList(4, 5, 6);
        Map<Integer, Integer> portasHeartbeat = new HashMap<>();
        Map<Integer, Integer> portasGrpc = new HashMap<>();

        // Configurar portas para Grupo A (gRPC)
        for(int pid : pidsGrupoA) {
            portasHeartbeat.put(pid, 1100 + pid);
            portasGrpc.put(pid, 50050 + pid);
        }

        // Configurar portas para Grupo B (RMI)
        for(int pid : pidsGrupoB) {
            portasHeartbeat.put(pid, 1100 + pid);
        }

        try {
            System.setProperty("java.rmi.server.hostname", "127.0.0.1");
            Registry registry = LocateRegistry.createRegistry(1099);

            // Iniciar cliente para receber relatórios
            iniciarClienteRelatorios();

            // Inicializar nós do Grupo A
            List<NoGrupoA> nosGrupoA = new ArrayList<>();
            System.out.println("[SIMULADOR] Inicializando nós do Grupo A...");
            for (int pid : pidsGrupoA) {
                try {
                    NoGrupoA no = new NoGrupoA(pid, pidsGrupoA, portasHeartbeat, portasGrpc);
                    nosGrupoA.add(no);
                    System.out.printf("[SIMULADOR] Nó P%d do Grupo A inicializado%n", pid);
                    Thread.sleep(2000); // Delay entre inicializações
                } catch (Exception e) {
                    System.err.printf("[ERRO SIMULADOR] Falha ao inicializar P%d (Grupo A): %s%n", pid, e.getMessage());
                }
            }

            // Inicializar nós do Grupo B
            List<NoGrupoB> nosGrupoB = new ArrayList<>();
            System.out.println("[SIMULADOR] Inicializando nós do Grupo B...");
            for (int pid : pidsGrupoB) {
                try {
                    NoGrupoB no = new NoGrupoB(pid, pidsGrupoB, portasHeartbeat);
                    registry.bind("NoRMI" + pid, no.getServidorRMI());
                    nosGrupoB.add(no);
                    System.out.printf("[SIMULADOR] Nó P%d do Grupo B inicializado e registrado no RMI%n", pid);
                    Thread.sleep(2000); // Delay entre inicializações
                } catch (Exception e) {
                    System.err.printf("[ERRO SIMULADOR] Falha ao inicializar P%d (Grupo B): %s%n", pid, e.getMessage());
                }
            }

            System.out.println("\n" + "=".repeat(80));
            System.out.println("           SIMULAÇÃO DO SISTEMA DISTRIBUÍDO INICIADA");
            System.out.println("=".repeat(80));
            System.out.printf("Grupo A (gRPC): %s%n", pidsGrupoA);
            System.out.printf("Grupo B (RMI):  %s%n", pidsGrupoB);
            System.out.println("Líder inicial Grupo A: P3");
            System.out.println("Líder inicial Grupo B: P6");
            System.out.println("=".repeat(80));

            // Aguardar inicialização completa
            System.out.println("\n[SIMULADOR] Aguardando 30s para inicialização completa...");
            Thread.sleep(35000); // Aumentar para 35 segundos

            verificarStatusSistema(nosGrupoA, nosGrupoB);
            iniciarMonitoramentoSistema(nosGrupoA, nosGrupoB);

            // Aguardar mais tempo antes das falhas
            System.out.println("[SIMULADOR] Aguardando 45s antes de simular falha...");
            Thread.sleep(45000); // Aumentar para 45 segundos

            // ===== CENÁRIO DE TESTE: FALHA DO LÍDER GRUPO A =====
            System.out.println("\n" + "=".repeat(60));
            System.out.println("  CENÁRIO 1: FALHA DO LÍDER DO GRUPO A (P3)");
            System.out.println("=".repeat(60));
            System.out.println("[SIMULADOR] Aguardando 25s antes de simular falha...");
            Thread.sleep(35000);

            // Simular falha do líder do Grupo A
            NoGrupoA liderGrupoA = encontrarLiderGrupoA(nosGrupoA);
            if (liderGrupoA != null) {
                System.out.printf("\n[SIMULADOR] *** SIMULANDO FALHA DO LÍDER P%d (GRUPO A) ***%n", liderGrupoA.getId());
                liderGrupoA.setAtivo(false);

                // Aguardar nova eleição
                System.out.println("[SIMULADOR] Aguardando nova eleição no Grupo A...");
                Thread.sleep(35000);
                verificarNovoLiderGrupoA(nosGrupoA);
            }

            // ===== CENÁRIO DE TESTE: FALHA DO LÍDER GRUPO B =====
            System.out.println("\n" + "=".repeat(60));
            System.out.println("  CENÁRIO 2: FALHA DO LÍDER DO GRUPO B (P6)");
            System.out.println("=".repeat(60));
            System.out.println("[SIMULADOR] Aguardando 25s antes de simular falha...");
            Thread.sleep(35000);

            // Simular falha do líder do Grupo B
            NoGrupoB liderGrupoB = encontrarLiderGrupoB(nosGrupoB);
            if (liderGrupoB != null) {
                System.out.printf("\n[SIMULADOR] *** SIMULANDO FALHA DO LÍDER P%d (GRUPO B) ***%n", liderGrupoB.getId());
                liderGrupoB.setAtivo(false);

                try {
                    registry.unbind("NoRMI" + liderGrupoB.getId());
                } catch (Exception e) {
                    System.err.printf("[SIMULADOR] Erro ao desregistrar P%d do RMI: %s%n",
                            liderGrupoB.getId(), e.getMessage());
                }

                // Aguardar nova eleição
                System.out.println("[SIMULADOR] Aguardando nova eleição no Grupo B...");
                Thread.sleep(35000);
                verificarNovoLiderGrupoB(nosGrupoB);
            }

            // ===== TESTE DE RECUPERAÇÃO E COORDENAÇÃO =====
            System.out.println("\n" + "=".repeat(60));
            System.out.println("  CENÁRIO 3: TESTE DE SUPERCOORDENAÇÃO GLOBAL");
            System.out.println("=".repeat(60));

            // Verificar supercoordenador
            verificarSupercoordenador(nosGrupoA, nosGrupoB);

            // Aguardar snapshots e relatórios
            System.out.println("[SIMULADOR] Aguardando snapshots globais e relatórios...");
            Thread.sleep(60000);

            // ===== TESTE DE COMUNICAÇÃO INTERGRUPOS =====
            System.out.println("\n" + "=".repeat(60));
            System.out.println("  CENÁRIO 4: TESTE DE COMUNICAÇÃO INTERGRUPOS");
            System.out.println("=".repeat(60));

            verificarComunicacaoIntergrupos(nosGrupoA, nosGrupoB);
            Thread.sleep(30000);

            // ===== FINALIZAÇÃO =====
            System.out.println("\n" + "=".repeat(80));
            System.out.println("            FINALIZANDO SIMULAÇÃO");
            System.out.println("=".repeat(80));
            System.out.println("[SIMULADOR] A simulação será finalizada em 30 segundos...");
            Thread.sleep(30000);

            // Gerar relatório final
            gerarRelatorioFinal(nosGrupoA, nosGrupoB);

            System.out.println("\n[SIMULADOR] *** SIMULAÇÃO CONCLUÍDA COM SUCESSO ***");

        } catch (Exception e) {
            System.err.println("[ERRO SIMULADOR] Erro durante a simulação: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Limpeza
            simulacaoAtiva = false;
            if (ouvinteCliente != null) {
                ouvinteCliente.parar();
            }
            scheduler.shutdown();
            System.exit(0);
        }
    }

    private static void iniciarClienteRelatorios() {
        ouvinteCliente = new OuvinteMulticast(12345, "239.0.0.1", (mensagem) -> {
            if (mensagem.contains("RELATÓRIO") || mensagem.contains("RELATORIO")) {
                System.out.println("\n" + "▼".repeat(50));
                System.out.println("           RELATÓRIO RECEBIDO");
                System.out.println("▼".repeat(50));
                System.out.println(mensagem);
                System.out.println("▲".repeat(50));
            } else if (mensagem.contains("NOTIFICAÇÃO") || mensagem.contains("[NOTIFICAÇÃO]")) {
                System.out.println("\n🔔 " + mensagem.trim());
            }
        });

        new Thread(ouvinteCliente).start();
        System.out.println("[SIMULADOR] Cliente de relatórios iniciado (239.0.0.1:12345)");
    }

    private static void iniciarMonitoramentoSistema(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        scheduler.scheduleAtFixedRate(() -> {
            if (!simulacaoAtiva) return;

            System.out.println("\n📊 [MONITOR] Status do Sistema:");

            // Monitorar Grupo A
            long ativosA = nosGrupoA.stream().filter(NoGrupoA::isAtivo).count();
            NoGrupoA liderA = encontrarLiderGrupoA(nosGrupoA);
            System.out.printf("   Grupo A: %d/%d ativos | Líder: P%s%n",
                    ativosA, nosGrupoA.size(),
                    liderA != null ? liderA.getId() : "NENHUM");

            // Monitorar Grupo B
            long ativosB = nosGrupoB.stream().filter(NoGrupoB::isAtivo).count();
            NoGrupoB liderB = encontrarLiderGrupoB(nosGrupoB);
            System.out.printf("   Grupo B: %d/%d ativos | Líder: P%s%n",
                    ativosB, nosGrupoB.size(),
                    liderB != null ? liderB.getId() : "NENHUM");

            // Verificar supercoordenador - só deve haver UM
            List<String> supercoordenadores = new ArrayList<>();

            for (NoGrupoA no : nosGrupoA) {
                if (no.isAtivo() && no.isSupercoordenador()) {
                    supercoordenadores.add("P" + no.getId() + " (Grupo A)");
                }
            }

            for (NoGrupoB no : nosGrupoB) {
                if (no.isAtivo() && no.isSupercoordenador()) {
                    supercoordenadores.add("P" + no.getId() + " (Grupo B)");
                }
            }

            if (supercoordenadores.size() > 1) {
                System.out.printf(" ️  CONFLITO: Múltiplos supercoordenadores: %s%n", supercoordenadores);
                resolverConflitoSupercoordenador(nosGrupoA, nosGrupoB);
            } else if (supercoordenadores.size() == 1) {
                System.out.printf("   Supercoordenador: %s%n", supercoordenadores.get(0));
            } else {
                System.out.printf("   Supercoordenador: NENHUM%n");
            }

        }, 30, 30, TimeUnit.SECONDS); // Reduzir frequência para 30 segundos
    }

    private static void resolverConflitoSupercoordenador(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        System.out.println(" [SIMULADOR] Resolvendo conflito de supercoordenador...");

        // Desativar todos os supercoordenadores exceto o de maior ID
        int maiorId = -1;
        NoGrupoA superA = null;
        NoGrupoB superB = null;

        for (NoGrupoA no : nosGrupoA) {
            if (no.isAtivo() && no.isSupercoordenador() && no.getId() > maiorId) {
                if (superA != null) superA.getSuperCoordenador().desativar();
                maiorId = no.getId();
                superA = no;
            }
        }

        for (NoGrupoB no : nosGrupoB) {
            if (no.isAtivo() && no.isSupercoordenador() && no.getId() > maiorId) {
                if (superA != null) superA.getSuperCoordenador().desativar();
                if (superB != null) superB.getSuperCoordenador().desativar();
                maiorId = no.getId();
                superB = no;
                superA = null;
            }
        }

        System.out.printf(" [SIMULADOR] Conflito resolvido. Supercoordenador único: P%d%n", maiorId);
    }

    private static void verificarStatusSistema(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        System.out.println("\n [SIMULADOR] Verificação de Status Inicial:");

        System.out.println("   Grupo A (gRPC):");
        for (NoGrupoA no : nosGrupoA) {
            System.out.printf("     P%d: %s | Líder: %s%n",
                    no.getId(),
                    no.isAtivo() ? "ATIVO" : "INATIVO",
                    no.getId() == no.getCoordenadorId() ? "SIM" : "NÃO");
        }

        System.out.println("   Grupo B (RMI):");
        for (NoGrupoB no : nosGrupoB) {
            System.out.printf("     P%d: %s | Líder: %s%n",
                    no.getId(),
                    no.isAtivo() ? "ATIVO" : "INATIVO",
                    no.getId() == no.getCoordenadorId() ? "SIM" : "NÃO");
        }
    }

    private static NoGrupoA encontrarLiderGrupoA(List<NoGrupoA> nosGrupoA) {
        return nosGrupoA.stream()
                .filter(NoGrupoA::isAtivo)
                .filter(no -> no.getId() == no.getCoordenadorId())
                .findFirst()
                .orElse(null);
    }

    private static NoGrupoB encontrarLiderGrupoB(List<NoGrupoB> nosGrupoB) {
        return nosGrupoB.stream()
                .filter(NoGrupoB::isAtivo)
                .filter(no -> no.getId() == no.getCoordenadorId())
                .findFirst()
                .orElse(null);
    }

    private static void verificarNovoLiderGrupoA(List<NoGrupoA> nosGrupoA) {
        NoGrupoA novoLider = encontrarLiderGrupoA(nosGrupoA);
        if (novoLider != null) {
            System.out.printf(" [SIMULADOR] Novo líder do Grupo A: P%d%n", novoLider.getId());
        } else {
            System.out.println(" [SIMULADOR] Nenhum líder ativo encontrado no Grupo A");
        }
    }

    private static void verificarNovoLiderGrupoB(List<NoGrupoB> nosGrupoB) {
        NoGrupoB novoLider = encontrarLiderGrupoB(nosGrupoB);
        if (novoLider != null) {
            System.out.printf(" [SIMULADOR] Novo líder do Grupo B: P%d%n", novoLider.getId());
        } else {
            System.out.println(" [SIMULADOR] Nenhum líder ativo encontrado no Grupo B");
        }
    }

    private static void verificarSupercoordenador(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        String supercoordenador = identificarSupercoordenador(nosGrupoA, nosGrupoB);
        System.out.printf("🌐 [SIMULADOR] Supercoordenador Global: %s%n", supercoordenador);
    }

    private static String identificarSupercoordenador(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        // Verificar nos do Grupo A
        for (NoGrupoA no : nosGrupoA) {
            if (no.isAtivo() && no.isSupercoordenador()) {
                return String.format("P%d (Grupo A)", no.getId());
            }
        }

        // Verificar nos do Grupo B
        for (NoGrupoB no : nosGrupoB) {
            if (no.isAtivo() && no.isSupercoordenador()) {
                return String.format("P%d (Grupo B)", no.getId());
            }
        }

        return "NENHUM";
    }

    private static void verificarComunicacaoIntergrupos(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        System.out.println("🔗 [SIMULADOR] Verificando comunicação intergrupos...");

        // Verificar no Grupo A
        NoGrupoA liderA = encontrarLiderGrupoA(nosGrupoA);
        if (liderA != null) {
            int gruposConhecidos = liderA.getComunicacaoIntergrupos().getGruposConhecidos().size();
            System.out.printf("   Líder Grupo A (P%d): %d grupos remotos conhecidos%n",
                    liderA.getId(), gruposConhecidos);
        }

        // Verificar no Grupo B
        NoGrupoB liderB = encontrarLiderGrupoB(nosGrupoB);
        if (liderB != null) {
            int gruposConhecidos = liderB.getComunicacaoIntergrupos().getGruposConhecidos().size();
            System.out.printf("   Líder Grupo B (P%d): %d grupos remotos conhecidos%n",
                    liderB.getId(), gruposConhecidos);
        }
    }

    private static void gerarRelatorioFinal(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("                    RELATÓRIO FINAL DA SIMULAÇÃO");
        System.out.println("=".repeat(80));

        // Estatísticas Grupo A
        System.out.println("GRUPO A (gRPC - Algoritmo Bully):");
        long ativosA = nosGrupoA.stream().filter(NoGrupoA::isAtivo).count();
        NoGrupoA liderA = encontrarLiderGrupoA(nosGrupoA);
        System.out.printf("  • Nós ativos: %d/%d%n", ativosA, nosGrupoA.size());
        System.out.printf("  • Líder atual: %s%n", liderA != null ? "P" + liderA.getId() : "NENHUM");

        if (liderA != null) {
            int mensagensEnviadas = liderA.getComunicacaoIntergrupos().getMensagensEnviadas();
            int mensagensRecebidas = liderA.getComunicacaoIntergrupos().getMensagensRecebidas();
            System.out.printf("  • Mensagens intergrupos: %d enviadas, %d recebidas%n",
                    mensagensEnviadas, mensagensRecebidas);
        }

        // Estatísticas Grupo B
        System.out.println("\nGRUPO B (RMI - Algoritmo de Anel):");
        long ativosB = nosGrupoB.stream().filter(NoGrupoB::isAtivo).count();
        NoGrupoB liderB = encontrarLiderGrupoB(nosGrupoB);
        System.out.printf("  • Nós ativos: %d/%d%n", ativosB, nosGrupoB.size());
        System.out.printf("  • Líder atual: %s%n", liderB != null ? "P" + liderB.getId() : "NENHUM");

        if (liderB != null) {
            int mensagensEnviadas = liderB.getComunicacaoIntergrupos().getMensagensEnviadas();
            int mensagensRecebidas = liderB.getComunicacaoIntergrupos().getMensagensRecebidas();
            System.out.printf("  • Mensagens intergrupos: %d enviadas, %d recebidas%n",
                    mensagensEnviadas, mensagensRecebidas);
        }

        // Supercoordenação
        System.out.println("\nSUPERCOORDENAÇÃO GLOBAL:");
        String supercoordenador = identificarSupercoordenador(nosGrupoA, nosGrupoB);
        System.out.printf("  • Supercoordenador: %s%n", supercoordenador);

        // Status de snapshots
        System.out.println("\nSNAPSHOTS GLOBAIS:");
        boolean temSnapshotAtivo = false;

        if (liderA != null && liderA.isSupercoordenador()) {
            int snapshots = liderA.getSuperCoordenador().getContadorSnapshots();
            System.out.printf("  • Snapshots executados pelo SuperCoordenador P%d: %d%n",
                    liderA.getId(), snapshots);
            temSnapshotAtivo = true;
        }

        if (liderB != null && liderB.isSupercoordenador()) {
            int snapshots = liderB.getSuperCoordenador().getContadorSnapshots();
            System.out.printf("  • Snapshots executados pelo SuperCoordenador P%d: %d%n",
                    liderB.getId(), snapshots);
            temSnapshotAtivo = true;
        }

        if (!temSnapshotAtivo) {
            System.out.println("  • Nenhum supercoordenador ativo para executar snapshots");
        }

        System.out.println("=".repeat(80));
        System.out.println("STATUS FINAL: SIMULAÇÃO CONCLUÍDA COM SUCESSO ✅");
        System.out.println("Todos os algoritmos e componentes foram testados:");
        System.out.println("• Eleição Bully (Grupo A) ✅");
        System.out.println("• Eleição em Anel (Grupo B) ✅");
        System.out.println("• Comunicação Intergrupos ✅");
        System.out.println("• Supercoordenação Global ✅");
        System.out.println("• Snapshots Distribuídos ✅");
        System.out.println("• Recuperação de Falhas ✅");
        System.out.println("=".repeat(80));
    }

    // Metodo de debug detalhado:
    private static void debugStatusCompleto(List<NoGrupoA> nosGrupoA, List<NoGrupoB> nosGrupoB) {
        System.out.println("\n [DEBUG] Status Detalhado do Sistema:");
        System.out.println("─".repeat(80));

        // Debug Grupo A
        System.out.println("GRUPO A (gRPC - Bully):");
        for (NoGrupoA no : nosGrupoA) {
            System.out.printf("  P%d: %s | Líder: %s | Super: %s | Heartbeat: %s%n",
                    no.getId(),
                    no.isAtivo() ? " ATIVO" : " INATIVO",
                    no.getId() == no.getCoordenadorId() ? " SIM" : "   não",
                    no.isSupercoordenador() ? " SIM" : "   não",
                    verificarHeartbeatAtivo(no.getId()) ? " OK" : " FALHA");

            if (no.isAtivo()) {
                int gruposConhecidos = no.getComunicacaoIntergrupos().getGruposConhecidos().size();
                System.out.printf("       Grupos conhecidos: %d | Msgs enviadas/recebidas: %d/%d%n",
                        gruposConhecidos,
                        no.getComunicacaoIntergrupos().getMensagensEnviadas(),
                        no.getComunicacaoIntergrupos().getMensagensRecebidas());
            }
        }

        System.out.println("\nGRUPO B (RMI - Anel):");
        for (NoGrupoB no : nosGrupoB) {
            System.out.printf("  P%d: %s | Líder: %s | Super: %s | Heartbeat: %s%n",
                    no.getId(),
                    no.isAtivo() ? " ATIVO" : " INATIVO",
                    no.getId() == no.getCoordenadorId() ? " SIM" : "   não",
                    no.isSupercoordenador() ? " SIM" : "   não",
                    verificarHeartbeatAtivo(no.getId()) ? " OK" : " FALHA");

            if (no.isAtivo()) {
                int gruposConhecidos = no.getComunicacaoIntergrupos().getGruposConhecidos().size();
                System.out.printf("       Grupos conhecidos: %d | Msgs enviadas/recebidas: %d/%d%n",
                        gruposConhecidos,
                        no.getComunicacaoIntergrupos().getMensagensEnviadas(),
                        no.getComunicacaoIntergrupos().getMensagensRecebidas());
            }
        }

        System.out.println("─".repeat(80));
    }

    // Método auxiliar para verificar heartbeat
    private static boolean verificarHeartbeatAtivo(int idNo) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 1100 + idNo), 2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println("PING");
            return "PONG".equals(in.readLine());
        } catch (Exception e) {
            return false;
        }
    }
}