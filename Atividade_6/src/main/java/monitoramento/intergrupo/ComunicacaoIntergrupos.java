package monitoramento.intergrupo;

import monitoramento.comum.Recurso;
import monitoramento.coordenacao.EmissorMulticast;
import monitoramento.coordenacao.OuvinteMulticast;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Classe responsável pela comunicação entre diferentes grupos (A e B)
 * Implementa descoberta, sincronização e coordenação intergrupos
 */
public class ComunicacaoIntergrupos {

    private final int idNo;
    private final String tipoGrupo;
    private final Supplier<Integer> relogioSupplier;
    private final Supplier<Boolean> isLiderSupplier;
    private final Consumer<String> processadorMensagens;
    private final Supplier<Recurso> recursoSupplier;

    // Comunicação multicast para intergrupos
    private final EmissorMulticast emissor = new EmissorMulticast();
    private final OuvinteMulticast ouvinte;

    // Controle de grupos conhecidos
    private final Map<Integer, InfoGrupoRemoto> gruposConhecidos = new ConcurrentHashMap<>();
    private final AtomicInteger mensagensEnviadas = new AtomicInteger(0);
    private final AtomicInteger mensagensRecebidas = new AtomicInteger(0);
    private final AtomicLong ultimoHeartbeatIntergrupos = new AtomicLong(System.currentTimeMillis());

    // Configurações de comunicação intergrupos
    private static final String ENDERECO_INTERGRUPOS = "239.0.0.4";
    private static final int PORTA_INTERGRUPOS = 12348;
    private static final long TIMEOUT_DESCOBERTA_MS = 30000; // 30 segundos
    private static final long INTERVALO_HEARTBEAT_MS = 20000; // 15 segundos

    public ComunicacaoIntergrupos(int idNo, String tipoGrupo,
                                  Supplier<Integer> relogioSupplier,
                                  Supplier<Boolean> isLiderSupplier,
                                  Consumer<String> processadorMensagens,
                                  Supplier<Recurso> recursoSupplier) {
        this.idNo = idNo;
        this.tipoGrupo = tipoGrupo;
        this.relogioSupplier = relogioSupplier;
        this.isLiderSupplier = isLiderSupplier;
        this.processadorMensagens = processadorMensagens;
        this.recursoSupplier = recursoSupplier;

        // Inicializar ouvinte para mensagens intergrupos
        this.ouvinte = new OuvinteMulticast(PORTA_INTERGRUPOS, ENDERECO_INTERGRUPOS,
                this::processarMensagemIntergrupos);
        new Thread(this.ouvinte).start();

        System.out.printf("[INTERGRUPOS P%d-%s] Comunicação intergrupos inicializada%n", idNo, tipoGrupo);
    }

    /**
     * Processa mensagens recebidas de outros grupos
     */
    private void processarMensagemIntergrupos(String mensagem) {
        mensagensRecebidas.incrementAndGet();

        try {
            String[] partes = mensagem.split(":", 3);
            if (partes.length < 2) return;

            String tipoMensagem = partes[0];
            String remetenteInfo = partes[1]; // formato: "idNo-tipoGrupo"

            // Extrair informações do remetente
            String[] infoRemetente = remetenteInfo.split("-");
            if (infoRemetente.length != 2) return;

            int idRemetente = Integer.parseInt(infoRemetente[0]);
            String grupoRemetente = infoRemetente[1];

            // Ignorar mensagens do próprio grupo
            if (grupoRemetente.equals(this.tipoGrupo)) {
                return;
            }

            // Atualizar informações do grupo remoto
            atualizarGrupoRemoto(idRemetente, grupoRemetente);

            System.out.printf("[INTERGRUPOS P%d-%s] Recebido de P%d-%s: %s%n",
                    idNo, tipoGrupo, idRemetente, grupoRemetente, tipoMensagem);

            // Processar diferentes tipos de mensagem
            switch (tipoMensagem) {
                case "PING_INTER":
                    processarPingIntergrupos(idRemetente, grupoRemetente);
                    break;

                case "PONG_INTER":
                    processarPongIntergrupos(idRemetente, grupoRemetente);
                    break;

                case "STATUS_REQUEST":
                    processarSolicitacaoStatus(idRemetente, grupoRemetente);
                    break;

                case "STATUS_RESPONSE":
                    processarRespostaStatus(idRemetente, grupoRemetente, partes.length > 2 ? partes[2] : "");
                    break;

                case "SUPER_CANDIDATE":
                    processarCandidaturaSuper(idRemetente, grupoRemetente, partes.length > 2 ? partes[2] : "");
                    break;

                case "SNAPSHOT_GLOBAL":
                    processarSnapshotGlobal(idRemetente, grupoRemetente, partes.length > 2 ? partes[2] : "");
                    break;

                default:
                    // Repassar para processador personalizado
                    if (processadorMensagens != null) {
                        processadorMensagens.accept(mensagem);
                    }
                    break;
            }

        } catch (Exception e) {
            System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro ao processar mensagem: %s%n",
                    idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Processa ping de outro grupo
     */
    private void processarPingIntergrupos(int idRemetente, String grupoRemetente) {
        if (isLiderSupplier.get()) {
            // Responder apenas se for líder do meu grupo
            enviarMensagemIntergrupos("PONG_INTER", "");
            System.out.printf("[INTERGRUPOS P%d-%s] Respondido ping de P%d-%s%n",
                    idNo, tipoGrupo, idRemetente, grupoRemetente);
        }
    }

    /**
     * Processa pong de outro grupo
     */
    private void processarPongIntergrupos(int idRemetente, String grupoRemetente) {
        System.out.printf("[INTERGRUPOS P%d-%s] Confirmação de vida de P%d-%s%n",
                idNo, tipoGrupo, idRemetente, grupoRemetente);
        ultimoHeartbeatIntergrupos.set(System.currentTimeMillis());
    }

    /**
     * Processa solicitação de status
     */
    private void processarSolicitacaoStatus(int idRemetente, String grupoRemetente) {
        if (isLiderSupplier.get()) {
            // Enviar status apenas se for líder
            Recurso recurso = recursoSupplier.get();
            String dadosStatus = String.format("relogio=%d,cpu=%.2f,memoria=%.2f",
                    recurso.getRelogioLamport(), recurso.getUsoCpu(), recurso.getUsoMemoria());

            enviarMensagemIntergrupos("STATUS_RESPONSE", dadosStatus);
        }
    }

    /**
     * Processa resposta de status
     */
    private void processarRespostaStatus(int idRemetente, String grupoRemetente, String dadosStatus) {
        System.out.printf("[INTERGRUPOS P%d-%s] Status recebido de P%d-%s: %s%n",
                idNo, tipoGrupo, idRemetente, grupoRemetente, dadosStatus);

        // Atualizar dados do grupo remoto
        InfoGrupoRemoto info = gruposConhecidos.get(idRemetente);
        if (info != null) {
            info.atualizarStatus(dadosStatus);
        }
    }

    /**
     * Processa candidatura para supercoordenador
     */
    private void processarCandidaturaSuper(int idRemetente, String grupoRemetente, String dadosCandidatura) {
        String mensagemProcessada = String.format("SUPER_ELECTION:candidato:%d-%s,prioridade:%s",
                idRemetente, grupoRemetente, dadosCandidatura);

        if (processadorMensagens != null) {
            processadorMensagens.accept(mensagemProcessada);
        }
    }

    /**
     * Processa snapshot global
     */
    private void processarSnapshotGlobal(int idRemetente, String grupoRemetente, String dadosSnapshot) {
        String mensagemProcessada = String.format("SNAPSHOT_MARKER:%s", dadosSnapshot);

        if (processadorMensagens != null) {
            processadorMensagens.accept(mensagemProcessada);
        }
    }

    /**
     * Atualiza informações de um grupo remoto
     */
    private void atualizarGrupoRemoto(int idRemetente, String grupoRemetente) {
        gruposConhecidos.computeIfAbsent(idRemetente,
                k -> new InfoGrupoRemoto(idRemetente, grupoRemetente));

        InfoGrupoRemoto info = gruposConhecidos.get(idRemetente);
        info.atualizarUltimoContato();
    }

    /**
     * Envia ping para descobrir outros grupos
     */
    public void enviarPingIntergrupo() {
        if (isLiderSupplier.get()) {
            enviarMensagemIntergrupos("PING_INTER", "");
            System.out.printf("[INTERGRUPOS P%d-%s] Ping enviado para descoberta de grupos%n", idNo, tipoGrupo);
        }
    }

    /**
     * Solicita status de outros grupos
     */
    public void solicitarStatusIntergrupo() {
        if (isLiderSupplier.get()) {
            enviarMensagemIntergrupos("STATUS_REQUEST", "");
        }
    }

    /**
     * Envia candidatura para supercoordenador
     */
    public void enviarCandidaturaSuper() {
        if (isLiderSupplier.get()) {
            String dadosCandidatura = String.format("relogio=%d,prioridade=%d",
                    relogioSupplier.get(), idNo);
            enviarMensagemIntergrupos("SUPER_CANDIDATE", dadosCandidatura);
            System.out.printf("[INTERGRUPOS P%d-%s] Candidatura para supercoordenador enviada%n", idNo, tipoGrupo);
        }
    }

    /**
     * Envia marcador de snapshot global
     */
    public void enviarMarcadorSnapshot(String idSnapshot) {
        if (isLiderSupplier.get()) {
            String dadosSnapshot = String.format("id=%s,relogio=%d,timestamp=%s",
                    idSnapshot, relogioSupplier.get(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
            enviarMensagemIntergrupos("SNAPSHOT_GLOBAL", dadosSnapshot);
        }
    }

    /**
     * Método genérico para enviar mensagens intergrupos
     */
    private void enviarMensagemIntergrupos(String tipo, String payload) {
        String remetenteInfo = String.format("%d-%s", idNo, tipoGrupo);
        String mensagem = payload.isEmpty() ?
                String.format("%s:%s", tipo, remetenteInfo) :
                String.format("%s:%s:%s", tipo, remetenteInfo, payload);

        emissor.enviarMensagem(mensagem, ENDERECO_INTERGRUPOS, PORTA_INTERGRUPOS);
        mensagensEnviadas.incrementAndGet();
    }

    /**
     * Gera relatório da comunicação intergrupos
     */
    public String gerarRelatorioIntergrupos() {
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(70)).append("\n");
        relatorio.append("         RELATÓRIO DE COMUNICAÇÃO INTERGRUPOS\n");
        relatorio.append("=".repeat(70)).append("\n");
        relatorio.append(String.format("Nó: P%d-%s | Status: %s%n",
                idNo, tipoGrupo, isLiderSupplier.get() ? "LÍDER" : "SEGUIDOR"));
        relatorio.append(String.format("Timestamp: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(70)).append("\n");

        // Estatísticas de comunicação
        relatorio.append("ESTATÍSTICAS DE COMUNICAÇÃO:\n");
        relatorio.append(String.format("  • Mensagens enviadas: %d%n", mensagensEnviadas.get()));
        relatorio.append(String.format("  • Mensagens recebidas: %d%n", mensagensRecebidas.get()));
        relatorio.append(String.format("  • Grupos conhecidos: %d%n", gruposConhecidos.size()));

        long ultimoHeartbeat = (System.currentTimeMillis() - ultimoHeartbeatIntergrupos.get()) / 1000;
        relatorio.append(String.format("  • Último heartbeat: %ds atrás%n", ultimoHeartbeat));

        // Grupos conhecidos
        relatorio.append("\nGRUPOS REMOTOS CONHECIDOS:\n");
        if (gruposConhecidos.isEmpty()) {
            relatorio.append("  • Nenhum grupo remoto descoberto\n");
        } else {
            gruposConhecidos.values().forEach(info -> {
                long tempoContato = (System.currentTimeMillis() - info.getUltimoContato()) / 1000;
                relatorio.append(String.format("  • P%d-%s: ativo há %ds (status: %s)%n",
                        info.getId(), info.getTipoGrupo(), tempoContato, info.getStatusInfo()));
            });
        }

        relatorio.append("-".repeat(70)).append("\n");
        relatorio.append("STATUS: COMUNICAÇÃO INTERGRUPOS ATIVA\n");
        relatorio.append("=".repeat(70)).append("\n");

        return relatorio.toString();
    }

    /**
     * Para a comunicação intergrupos
     */
    public void parar() {
        if (ouvinte != null) {
            ouvinte.parar();
        }
        System.out.printf("[INTERGRUPOS P%d-%s] Comunicação intergrupos encerrada%n", idNo, tipoGrupo);
    }

    // Getters
    public Map<Integer, InfoGrupoRemoto> getGruposConhecidos() {
        return new ConcurrentHashMap<>(gruposConhecidos);
    }

    public int getMensagensEnviadas() { return mensagensEnviadas.get(); }
    public int getMensagensRecebidas() { return mensagensRecebidas.get(); }

    /**
     * Classe para armazenar informações de grupos remotos
     */
    public static class InfoGrupoRemoto {
        private final int id;
        private final String tipoGrupo;
        private volatile long ultimoContato;
        private volatile String statusInfo = "DESCOBERTO";

        public InfoGrupoRemoto(int id, String tipoGrupo) {
            this.id = id;
            this.tipoGrupo = tipoGrupo;
            this.ultimoContato = System.currentTimeMillis();
            System.out.printf("[INTERGRUPOS] Grupo remoto registrado: P%d-%s%n", id, tipoGrupo);
        }

        public void atualizarUltimoContato() {
            this.ultimoContato = System.currentTimeMillis();
        }

        public void atualizarStatus(String novoStatus) {
            this.statusInfo = novoStatus;
            atualizarUltimoContato();
            System.out.printf("[INTERGRUPOS] Status atualizado P%d-%s: %s%n", id, tipoGrupo, novoStatus);
        }

        public boolean isAtivo(long timeoutMs) {
            long tempoDecorrido = System.currentTimeMillis() - ultimoContato;
            return tempoDecorrido < timeoutMs;
        }

        public long getTempoSemContato() {
            return (System.currentTimeMillis() - ultimoContato) / 1000; // em segundos
        }

        // Getters
        public int getId() { return id; }
        public String getTipoGrupo() { return tipoGrupo; }
        public long getUltimoContato() { return ultimoContato; }
        public String getStatusInfo() { return statusInfo; }
    }

}