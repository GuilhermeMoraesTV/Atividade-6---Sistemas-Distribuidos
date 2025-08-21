package monitoramento.comum;

import monitoramento.coordenacao.EmissorMulticast;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Gerencia recuperação e substituição dinâmica de nós com falha
 */
public class GestorRecuperacao {
    private final Supplier<Integer> idSupplier;
    private final Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier;
    private final Consumer<String> notificadorCallback;
    private final EmissorMulticast emissor = new EmissorMulticast();

    // Controle de recuperação
    private final Map<Integer, Long> horariosUltimaFalha = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> tentativasRecuperacao = new ConcurrentHashMap<>();
    private final AtomicBoolean recuperacaoAtiva = new AtomicBoolean(false);

    // Configurações
    private static final int MAX_TENTATIVAS_RECUPERACAO = 3;
    private static final long INTERVALO_RECUPERACAO_MS = 30000; // 30 segundos
    private static final long TIMEOUT_SUBSTITUICAO_MS = 60000; // 1 minuto

    public GestorRecuperacao(Supplier<Integer> idSupplier,
                             Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier,
                             Consumer<String> notificadorCallback) {
        this.idSupplier = idSupplier;
        this.nosDaRedeSupplier = nosDaRedeSupplier;
        this.notificadorCallback = notificadorCallback;

        // Iniciar thread de monitoramento de recuperação
        new Thread(this::monitorarRecuperacao).start();
    }

    /**
     * Registra uma falha de nó para posterior tentativa de recuperação
     */
    public void registrarFalha(int idNoFalho) {
        long agora = System.currentTimeMillis();
        horariosUltimaFalha.put(idNoFalho, agora);
        tentativasRecuperacao.put(idNoFalho, 0);

        String mensagem = String.format("NÓ P%d DETECTADO COMO FALHO", idNoFalho);
        notificarEvento(mensagem);

        System.out.printf("[RECUPERAÇÃO P%d] Nó P%d registrado como falho às %s%n",
                idSupplier.get(), idNoFalho,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /**
     * Registra recuperação bem-sucedida de um nó
     */
    public void registrarRecuperacao(int idNoRecuperado) {
        horariosUltimaFalha.remove(idNoRecuperado);
        tentativasRecuperacao.remove(idNoRecuperado);

        String mensagem = String.format("NÓ P%d RECUPERADO COM SUCESSO", idNoRecuperado);
        notificarEvento(mensagem);

        System.out.printf("[RECUPERAÇÃO P%d] Nó P%d recuperado com sucesso às %s%n",
                idSupplier.get(), idNoRecuperado,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /**
     * Thread de monitoramento de recuperação
     */
    private void monitorarRecuperacao() {
        while (true) {
            try {
                Thread.sleep(10000); // Verificar a cada 10 segundos
                processarRecuperacoes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Processa tentativas de recuperação de nós
     */
    private void processarRecuperacoes() {
        long agora = System.currentTimeMillis();
        Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();

        for (Map.Entry<Integer, Long> entry : horariosUltimaFalha.entrySet()) {
            int idNo = entry.getKey();
            long horaFalha = entry.getValue();

            // Verificar se é hora de tentar recuperação
            if (agora - horaFalha >= INTERVALO_RECUPERACAO_MS) {
                int tentativas = tentativasRecuperacao.getOrDefault(idNo, 0);

                if (tentativas < MAX_TENTATIVAS_RECUPERACAO) {
                    tentarRecuperarNo(idNo, tentativas + 1);
                } else if (agora - horaFalha >= TIMEOUT_SUBSTITUICAO_MS) {
                    // Após esgotar tentativas e timeout, considerar substituição
                    processarSubstituicaoNo(idNo);
                }
            }
        }
    }

    /**
     * Tenta recuperar um nó específico
     */
    private void tentarRecuperarNo(int idNo, int numeroTentativa) {
        tentativasRecuperacao.put(idNo, numeroTentativa);

        System.out.printf("[RECUPERAÇÃO P%d] Tentativa %d/%d de recuperar P%d%n",
                idSupplier.get(), numeroTentativa, MAX_TENTATIVAS_RECUPERACAO, idNo);

        // Simular tentativa de reconexão
        Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();
        InfoNo noInfo = nosDaRede.get(idNo);

        if (noInfo != null) {
            // Resetar contador de falhas para dar uma chance
            noInfo.resetarContadorFalhas();

            // Notificar sobre tentativa de recuperação
            String mensagem = String.format("TENTANDO RECUPERAR NÓ P%d (Tentativa %d/%d)",
                    idNo, numeroTentativa, MAX_TENTATIVAS_RECUPERACAO);
            notificarEvento(mensagem);
        }
    }

    /**
     * Processa a substituição de um nó que não pôde ser recuperado
     */
    private void processarSubstituicaoNo(int idNoFalho) {
        System.out.printf("[RECUPERAÇÃO P%d] Nó P%d não pôde ser recuperado. Iniciando processo de substituição...%n",
                idSupplier.get(), idNoFalho);

        // Simular criação de nó substituto (na prática, seria um novo processo)
        int idSubstituto = idNoFalho + 100; // ID temporário para identificar substituto

        // Gerar relatório de substituição
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(60)).append("\n");
        relatorio.append("           RELATÓRIO DE SUBSTITUIÇÃO DE NÓ\n");
        relatorio.append("=".repeat(60)).append("\n");
        relatorio.append(String.format("Nó Original: P%d (FALHOU PERMANENTEMENTE)%n", idNoFalho));
        relatorio.append(String.format("Nó Substituto: P%d (SIMULADO)%n", idSubstituto));
        relatorio.append(String.format("Data/Hora: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append(String.format("Tentativas de Recuperação: %d%n", MAX_TENTATIVAS_RECUPERACAO));
        relatorio.append("-".repeat(60)).append("\n");
        relatorio.append("STATUS: NÓ SUBSTITUÍDO COM SUCESSO (SIMULAÇÃO)\n");
        relatorio.append("AÇÕES REALIZADAS:\n");
        relatorio.append("• Nó original removido da rede\n");
        relatorio.append("• Nó substituto configurado\n");
        relatorio.append("• Estados sincronizados\n");
        relatorio.append("• Heartbeat reestabelecido\n");
        relatorio.append("=".repeat(60)).append("\n");

        // Enviar relatório
        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);
        System.out.print(relatorio.toString());

        // Limpar registros do nó falho
        horariosUltimaFalha.remove(idNoFalho);
        tentativasRecuperacao.remove(idNoFalho);

        // Notificar sobre substituição
        String mensagem = String.format("NÓ P%d SUBSTITUÍDO POR P%d (SIMULAÇÃO)", idNoFalho, idSubstituto);
        notificarEvento(mensagem);
    }

    /**
     * Envia notificação de evento via multicast
     */
    private void notificarEvento(String evento) {
        if (notificadorCallback != null) {
            notificadorCallback.accept(evento);
        }

        emissor.enviarNotificacao(evento, idSupplier.get());
    }

    /**
     * Gera relatório de status do sistema de recuperação
     */
    public void gerarRelatorioRecuperacao() {
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(50)).append("\n");
        relatorio.append("     RELATÓRIO DO SISTEMA DE RECUPERAÇÃO\n");
        relatorio.append("=".repeat(50)).append("\n");
        relatorio.append(String.format("Monitor: P%d%n", idSupplier.get()));
        relatorio.append(String.format("Data/Hora: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(50)).append("\n");

        if (horariosUltimaFalha.isEmpty()) {
            relatorio.append("• Nenhuma falha registrada no momento\n");
        } else {
            relatorio.append("FALHAS ATIVAS:\n");
            horariosUltimaFalha.forEach((id, tempo) -> {
                int tentativas = tentativasRecuperacao.getOrDefault(id, 0);
                long tempoDecorrido = (System.currentTimeMillis() - tempo) / 1000;
                relatorio.append(String.format("  • P%d: %d tentativas, %ds desde falha%n",
                        id, tentativas, tempoDecorrido));
            });
        }

        relatorio.append("-".repeat(50)).append("\n");
        relatorio.append(String.format("Configurações: Max tentativas=%d, Intervalo=%ds%n",
                MAX_TENTATIVAS_RECUPERACAO, INTERVALO_RECUPERACAO_MS/1000));
        relatorio.append("=".repeat(50)).append("\n");

        System.out.print(relatorio.toString());
        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);
    }

    // Getters para status
    public Map<Integer, Integer> getTentativasRecuperacao() {
        return new ConcurrentHashMap<>(tentativasRecuperacao);
    }

    public boolean existemFalhasAtivas() {
        return !horariosUltimaFalha.isEmpty();
    }
}