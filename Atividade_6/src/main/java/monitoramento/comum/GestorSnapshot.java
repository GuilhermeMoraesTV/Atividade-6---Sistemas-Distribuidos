package monitoramento.comum;

import monitoramento.coordenacao.EmissorMulticast;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Implementação completa do algoritmo de Chandy-Lamport para captura de estado global
 */
public class GestorSnapshot {
    private final Supplier<Integer> idSupplier;
    private final Supplier<Integer> relogioSupplier;
    private final Supplier<Boolean> isAtivoSupplier;
    private final EmissorMulticast emissor;

    // Estado do snapshot
    private final AtomicBoolean capturaAtiva = new AtomicBoolean(false);
    private final AtomicInteger estadoLocal = new AtomicInteger(0);
    private final Map<Integer, Boolean> marcadoresRecebidos = new ConcurrentHashMap<>();
    private final Map<Integer, String> estadosCanais = new ConcurrentHashMap<>();
    private final Map<Integer, StringBuilder> bufferMensagens = new ConcurrentHashMap<>();

    // Configurações
    private static final String ENDERECO_SNAPSHOT = "239.0.0.3";
    private static final int PORTA_SNAPSHOT = 12347;

    public GestorSnapshot(Supplier<Integer> idSupplier, Supplier<Integer> relogioSupplier,
                          Supplier<Boolean> isAtivoSupplier) {
        this.idSupplier = idSupplier;
        this.relogioSupplier = relogioSupplier;
        this.isAtivoSupplier = isAtivoSupplier;
        this.emissor = new EmissorMulticast();
    }

    /**
     * Inicia a captura de estado global (chamado pelo supercoordenador)
     */
    public void iniciarCapturaEstado() {
        if (!capturaAtiva.compareAndSet(false, true)) {
            System.out.printf("[SNAPSHOT P%d] Captura já está em andamento%n", idSupplier.get());
            return;
        }

        // 1. Salvar estado local
        estadoLocal.set(relogioSupplier.get());
        marcadoresRecebidos.clear();
        estadosCanais.clear();
        bufferMensagens.clear();

        System.out.printf("[SNAPSHOT P%d] INICIANDO CAPTURA DE ESTADO GLOBAL%n", idSupplier.get());
        System.out.printf("[SNAPSHOT P%d] Estado local capturado: %d%n", idSupplier.get(), estadoLocal.get());

        // 2. Enviar marcadores para todos os outros processos
        String mensagemMarcador = String.format("MARCADOR:%d:%d:%s",
                idSupplier.get(), relogioSupplier.get(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));

        emissor.enviarMensagem(mensagemMarcador, ENDERECO_SNAPSHOT, PORTA_SNAPSHOT);

        // 3. Iniciar gravação de mensagens dos canais
        iniciarGravacaoCanais();

        // 4. Agendar finalização do snapshot após timeout
        new Thread(this::finalizarSnapshotAposTimeout).start();
    }

    /**
     * Processa o recebimento de um marcador
     */
    public void receberMarcador(int idRemetente, int relogioRemetente, String timestamp) {
        System.out.printf("[SNAPSHOT P%d] Marcador recebido de P%d (relógio: %d, time: %s)%n",
                idSupplier.get(), idRemetente, relogioRemetente, timestamp);

        // Se é o primeiro marcador, captura o estado local
        if (capturaAtiva.compareAndSet(false, true)) {
            estadoLocal.set(relogioSupplier.get());
            System.out.printf("[SNAPSHOT P%d] Primeiro marcador! Estado local capturado: %d%n",
                    idSupplier.get(), estadoLocal.get());

            // Propagar o marcador para outros processos
            String mensagemMarcador = String.format("MARCADOR:%d:%d:%s",
                    idSupplier.get(), relogioSupplier.get(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));

            emissor.enviarMensagem(mensagemMarcador, ENDERECO_SNAPSHOT, PORTA_SNAPSHOT);
            iniciarGravacaoCanais();
        }

        // Marcar que recebeu marcador deste canal
        marcadoresRecebidos.put(idRemetente, true);

        // Finalizar gravação para este canal específico
        finalizarGravacaoCanal(idRemetente);

        // Verificar se todos os marcadores foram recebidos
        verificarCompletude();
    }

    /**
     * Registra uma mensagem recebida durante a captura
     */
    public void registrarMensagemCanal(int idRemetente, String mensagem) {
        if (!capturaAtiva.get()) return;

        // Se ainda não recebeu marcador deste canal, gravar mensagem
        if (!marcadoresRecebidos.getOrDefault(idRemetente, false)) {
            bufferMensagens.computeIfAbsent(idRemetente, k -> new StringBuilder())
                    .append(String.format("[%s] %s%n",
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                            mensagem));

            System.out.printf("[SNAPSHOT P%d] Mensagem do canal P%d gravada durante snapshot%n",
                    idSupplier.get(), idRemetente);
        }
    }

    /**
     * Inicia a gravação de mensagens de todos os canais
     */
    private void iniciarGravacaoCanais() {
        System.out.printf("[SNAPSHOT P%d] Iniciando gravação de canais de comunicação%n", idSupplier.get());
        // A gravação é feita através do método registrarMensagemCanal chamado externamente
    }

    /**
     * Finaliza a gravação de um canal específico
     */
    private void finalizarGravacaoCanal(int idCanal) {
        StringBuilder buffer = bufferMensagens.get(idCanal);
        String estadoCanal = (buffer != null && buffer.length() > 0) ?
                buffer.toString() : "VAZIO";

        estadosCanais.put(idCanal, estadoCanal);

        System.out.printf("[SNAPSHOT P%d] Canal P%d finalizado. Estado: %s%n",
                idSupplier.get(), idCanal,
                estadoCanal.equals("VAZIO") ? "VAZIO" : "COM_MENSAGENS");
    }

    /**
     * Verifica se o snapshot está completo
     */
    private void verificarCompletude() {
        // Para simplificar, assumimos que temos 6 processos total (3 de cada grupo)
        // e cada processo deve receber marcadores dos outros 5
        int totalEsperados = 5; // Ajustar conforme necessário

        if (marcadoresRecebidos.size() >= totalEsperados) {
            finalizarSnapshot();
        }
    }

    /**
     * Finaliza o snapshot e gera relatório
     */
    private void finalizarSnapshot() {
        if (!capturaAtiva.getAndSet(false)) return;

        System.out.printf("%n[SNAPSHOT P%d] *** CAPTURA DE ESTADO COMPLETADA ***%n", idSupplier.get());

        // Gerar relatório do snapshot
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(70)).append("\n");
        relatorio.append("           RELATÓRIO DE SNAPSHOT GLOBAL (Chandy-Lamport)\n");
        relatorio.append("=".repeat(70)).append("\n");
        relatorio.append(String.format("Processo: P%d | Estado Local: %d | Timestamp: %s%n",
                idSupplier.get(), estadoLocal.get(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(70)).append("\n");

        relatorio.append("ESTADO DOS CANAIS DE COMUNICAÇÃO:\n");
        if (estadosCanais.isEmpty()) {
            relatorio.append("  • Todos os canais estão vazios\n");
        } else {
            estadosCanais.forEach((canal, estado) -> {
                relatorio.append(String.format("  • Canal P%d->P%d: %s%n",
                        canal, idSupplier.get(),
                        estado.equals("VAZIO") ? "VAZIO" : "CONTÉM MENSAGENS"));
            });
        }

        relatorio.append("-".repeat(70)).append("\n");
        relatorio.append(String.format("Marcadores recebidos de: %s%n", marcadoresRecebidos.keySet()));
        relatorio.append(String.format("Total de canais monitorados: %d%n", estadosCanais.size()));
        relatorio.append("=".repeat(70)).append("\n");

        // Enviar relatório via multicast
        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);

        System.out.print(relatorio.toString());
    }

    /**
     * Finaliza snapshot por timeout se necessário
     */
    private void finalizarSnapshotAposTimeout() {
        try {
            Thread.sleep(15000); // 15 segundos de timeout
            if (capturaAtiva.get()) {
                System.out.printf("[SNAPSHOT P%d] Timeout atingido, finalizando snapshot%n", idSupplier.get());
                finalizarSnapshot();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Getters para status
    public boolean isCapturaAtiva() { return capturaAtiva.get(); }
    public int getEstadoLocal() { return estadoLocal.get(); }
    public Map<Integer, String> getEstadosCanais() { return new ConcurrentHashMap<>(estadosCanais); }
}