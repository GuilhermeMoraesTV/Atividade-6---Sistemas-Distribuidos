package monitoramento.comum;

import monitoramento.coordenacao.EmissorMulticast;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Gerador de nós substitutos para recuperação dinâmica do sistema
 * Simula a criação de novos processos quando nós falham permanentemente
 */
public class GeradorNosSubstitutos {

    private final Supplier<Integer> idSupplier;
    private final Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier;
    private final Consumer<String> notificadorCallback;
    private final EmissorMulticast emissor = new EmissorMulticast();

    // Controle de substituições
    private final Map<Integer, Integer> mapaSubstituicoes = new ConcurrentHashMap<>();
    private final AtomicInteger contadorSubstitutos = new AtomicInteger(1000); // IDs a partir de 1000
    private final Map<Integer, Long> timestampSubstituicoes = new ConcurrentHashMap<>();

    // Configurações
    private static final long TIMEOUT_SUBSTITUICAO_MS = 120000; // 2 minutos
    private static final int MAX_TENTATIVAS_ANTES_SUBSTITUICAO = 5;

    public GeradorNosSubstitutos(Supplier<Integer> idSupplier,
                                 Supplier<Map<Integer, InfoNo>> nosDaRedeSupplier,
                                 Consumer<String> notificadorCallback) {
        this.idSupplier = idSupplier;
        this.nosDaRedeSupplier = nosDaRedeSupplier;
        this.notificadorCallback = notificadorCallback;
    }

    /**
     * Gera um nó substituto para um nó que falhou permanentemente
     */
    public NoSubstituto gerarNoSubstituto(int idNoOriginal, String tipoGrupo) {
        int idSubstituto = contadorSubstitutos.incrementAndGet();
        long timestamp = System.currentTimeMillis();

        // Registrar substituição
        mapaSubstituicoes.put(idNoOriginal, idSubstituto);
        timestampSubstituicoes.put(idSubstituto, timestamp);

        // Criar informações do nó substituto
        NoSubstituto substituto = new NoSubstituto(
                idSubstituto,
                idNoOriginal,
                tipoGrupo,
                timestamp,
                calcularPortaHeartbeat(idSubstituto),
                calcularPortaServico(idSubstituto, tipoGrupo)
        );

        // Gerar relatório de substituição
        gerarRelatorioSubstituicao(substituto);

        // Notificar sobre a criação do substituto
        notificarEvento(String.format("NÓ SUBSTITUTO CRIADO: P%d substitui P%d (Grupo %s)",
                idSubstituto, idNoOriginal, tipoGrupo));

        System.out.printf("[GERADOR P%d] Nó substituto P%d criado para substituir P%d (Grupo %s)%n",
                idSupplier.get(), idSubstituto, idNoOriginal, tipoGrupo);

        return substituto;
    }

    /**
     * Verifica se um nó precisa de substituição baseado em critérios
     */
    public boolean precisaSubstituicao(int idNo, int tentativasRecuperacao, long tempoSemResposta) {
        // Já foi substituído?
        if (mapaSubstituicoes.containsKey(idNo)) {
            return false;
        }

        // Critérios para substituição
        boolean muitasTentativas = tentativasRecuperacao >= MAX_TENTATIVAS_ANTES_SUBSTITUICAO;
        boolean timeoutExcedido = tempoSemResposta >= TIMEOUT_SUBSTITUICAO_MS;

        return muitasTentativas && timeoutExcedido;
    }

    /**
     * Simula a inicialização de um nó substituto
     */
    public void inicializarNoSubstituto(NoSubstituto substituto) {
        System.out.printf("[GERADOR P%d] Inicializando nó substituto P%d...%n",
                idSupplier.get(), substituto.getIdSubstituto());

        // Simular processo de inicialização
        try {
            Thread.sleep(2000); // Simula tempo de inicialização

            // Marcar como ativo na rede (simulação)
            Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();
            InfoNo infoSubstituto = new InfoNo(substituto.getIdSubstituto(),
                    substituto.getPortaHeartbeat());
            infoSubstituto.setAtivo(true);
            infoSubstituto.resetarContadorFalhas();

            System.out.printf("[GERADOR P%d] Nó substituto P%d inicializado com sucesso%n",
                    idSupplier.get(), substituto.getIdSubstituto());

            notificarEvento(String.format("NÓ SUBSTITUTO INICIALIZADO: P%d (Grupo %s)",
                    substituto.getIdSubstituto(), substituto.getTipoGrupo()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[ERRO GERADOR P%d] Falha na inicialização do substituto P%d%n",
                    idSupplier.get(), substituto.getIdSubstituto());
        }
    }

    /**
     * Calcula porta de heartbeat para o nó substituto
     */
    private int calcularPortaHeartbeat(int idSubstituto) {
        return 2000 + idSubstituto; // Portas a partir de 3000
    }

    /**
     * Calcula porta de serviço baseada no tipo de grupo
     */
    private int calcularPortaServico(int idSubstituto, String tipoGrupo) {
        if ("A".equals(tipoGrupo)) {
            return 60000 + idSubstituto; // gRPC para Grupo A
        } else {
            return 70000 + idSubstituto; // RMI para Grupo B (simulação)
        }
    }

    /**
     * Gera relatório detalhado da substituição
     */
    private void gerarRelatorioSubstituicao(NoSubstituto substituto) {
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(80)).append("\n");
        relatorio.append("              RELATÓRIO DE SUBSTITUIÇÃO DINÂMICA DE NÓ\n");
        relatorio.append("=".repeat(80)).append("\n");
        relatorio.append(String.format("Nó Original: P%d (FALHOU PERMANENTEMENTE)%n",
                substituto.getIdOriginal()));
        relatorio.append(String.format("Nó Substituto: P%d (GERADO DINAMICAMENTE)%n",
                substituto.getIdSubstituto()));
        relatorio.append(String.format("Tipo de Grupo: %s%n", substituto.getTipoGrupo()));
        relatorio.append(String.format("Timestamp: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(80)).append("\n");

        relatorio.append("CONFIGURAÇÕES DO NÓ SUBSTITUTO:\n");
        relatorio.append(String.format("  • ID: %d%n", substituto.getIdSubstituto()));
        relatorio.append(String.format("  • Porta Heartbeat: %d%n", substituto.getPortaHeartbeat()));
        relatorio.append(String.format("  • Porta Serviço: %d%n", substituto.getPortaServico()));
        relatorio.append(String.format("  • Status: %s%n", substituto.getStatus()));

        relatorio.append("\nPROCESSOS REALIZADOS:\n");
        relatorio.append("  ✓ Nó original removido da rede ativa\n");
        relatorio.append("  ✓ ID único gerado para substituto\n");
        relatorio.append("  ✓ Portas de comunicação alocadas\n");
        relatorio.append("  ✓ Configuração de rede atualizada\n");
        relatorio.append("  ✓ Heartbeat configurado\n");

        relatorio.append("\nESTATÍSTICAS GLOBAIS:\n");
        relatorio.append(String.format("  • Total de substituições: %d%n", mapaSubstituicoes.size()));
        relatorio.append(String.format("  • Substitutos ativos: %d%n", contarSubstitutosAtivos()));

        relatorio.append("-".repeat(80)).append("\n");
        relatorio.append("STATUS: SUBSTITUIÇÃO REALIZADA COM SUCESSO\n");
        relatorio.append("=".repeat(80)).append("\n");

        // Enviar relatório via multicast
        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);
        System.out.print(relatorio.toString());
    }

    /**
     * Conta quantos substitutos estão atualmente ativos
     */
    private int contarSubstitutosAtivos() {
        Map<Integer, InfoNo> nosDaRede = nosDaRedeSupplier.get();
        return (int) timestampSubstituicoes.keySet().stream()
                .filter(id -> {
                    InfoNo info = nosDaRede.get(id);
                    return info != null && info.isAtivo();
                })
                .count();
    }

    /**
     * Remove substituição quando nó original se recupera
     */
    public void removerSubstituicao(int idNoOriginal) {
        Integer idSubstituto = mapaSubstituicoes.remove(idNoOriginal);
        if (idSubstituto != null) {
            timestampSubstituicoes.remove(idSubstituto);

            System.out.printf("[GERADOR P%d] Substituição removida: P%d (substituto P%d)%n",
                    idSupplier.get(), idNoOriginal, idSubstituto);

            notificarEvento(String.format("SUBSTITUIÇÃO REMOVIDA: P%d recuperou, P%d desativado",
                    idNoOriginal, idSubstituto));
        }
    }

    /**
     * Gera relatório de status do gerador
     */
    public void gerarRelatorioStatus() {
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(70)).append("\n");
        relatorio.append("        RELATÓRIO DO GERADOR DE NÓS SUBSTITUTOS\n");
        relatorio.append("=".repeat(70)).append("\n");
        relatorio.append(String.format("Gerador: P%d%n", idSupplier.get()));
        relatorio.append(String.format("Timestamp: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(70)).append("\n");

        if (mapaSubstituicoes.isEmpty()) {
            relatorio.append("• Nenhuma substituição ativa no momento\n");
        } else {
            relatorio.append("SUBSTITUIÇÕES ATIVAS:\n");
            mapaSubstituicoes.forEach((original, substituto) -> {
                long tempoAtivo = (System.currentTimeMillis() -
                        timestampSubstituicoes.getOrDefault(substituto, 0L)) / 1000;
                relatorio.append(String.format("  • P%d → P%d (ativo há %ds)%n",
                        original, substituto, tempoAtivo));
            });
        }

        relatorio.append("-".repeat(70)).append("\n");
        relatorio.append(String.format("Total de substituições criadas: %d%n",
                contadorSubstitutos.get() - 1000));
        relatorio.append(String.format("Substitutos ativos: %d%n", contarSubstitutosAtivos()));
        relatorio.append("=".repeat(70)).append("\n");

        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);
        System.out.print(relatorio.toString());
    }

    /**
     * Notifica eventos importantes
     */
    private void notificarEvento(String evento) {
        if (notificadorCallback != null) {
            notificadorCallback.accept(evento);
        }
        emissor.enviarNotificacao(evento, idSupplier.get());
    }

    // Getters
    public Map<Integer, Integer> getMapaSubstituicoes() {
        return new ConcurrentHashMap<>(mapaSubstituicoes);
    }

    public int getTotalSubstituicoes() {
        return contadorSubstitutos.get() - 1000;
    }

    /**
     * Classe interna para representar um nó substituto
     */
    public static class NoSubstituto {
        private final int idSubstituto;
        private final int idOriginal;
        private final String tipoGrupo;
        private final long timestampCriacao;
        private final int portaHeartbeat;
        private final int portaServico;
        private String status = "CRIADO";

        public NoSubstituto(int idSubstituto, int idOriginal, String tipoGrupo,
                            long timestampCriacao, int portaHeartbeat, int portaServico) {
            this.idSubstituto = idSubstituto;
            this.idOriginal = idOriginal;
            this.tipoGrupo = tipoGrupo;
            this.timestampCriacao = timestampCriacao;
            this.portaHeartbeat = portaHeartbeat;
            this.portaServico = portaServico;
        }

        // Getters
        public int getIdSubstituto() { return idSubstituto; }
        public int getIdOriginal() { return idOriginal; }
        public String getTipoGrupo() { return tipoGrupo; }
        public long getTimestampCriacao() { return timestampCriacao; }
        public int getPortaHeartbeat() { return portaHeartbeat; }
        public int getPortaServico() { return portaServico; }
        public String getStatus() { return status; }

        public void setStatus(String status) { this.status = status; }

        @Override
        public String toString() {
            return String.format("NoSubstituto{id=%d, original=%d, grupo=%s, status=%s}",
                    idSubstituto, idOriginal, tipoGrupo, status);
        }
    }
}