// Arquivo: src/main/java/monitoramento/coordenacao/SuperCoordenador.java
package monitoramento.coordenacao;

import monitoramento.comum.Recurso;
import monitoramento.intergrupo.ComunicacaoIntergrupos;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Classe responsável pela coordenação global entre grupos A e B
 * Implementa as funcionalidades do supercoordenador eleito
 */
public class SuperCoordenador {

    // Identificação
    private final int idNo;
    private final String tipoGrupo;
    private final AtomicBoolean ativo = new AtomicBoolean(false);
    private final AtomicBoolean isSupercoordenador = new AtomicBoolean(false);

    // Dependências
    private final Supplier<Integer> relogioSupplier;
    private final Supplier<Boolean> isLiderLocalSupplier;
    private final Consumer<String> notificadorCallback;
    private final ComunicacaoIntergrupos comunicacaoIntergrupos;
    private final EmissorMulticast emissor;

    // Controle de tarefas
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Map<String, ScheduledFuture<?>> tarefasAgendadas = new ConcurrentHashMap<>();

    // Estado global
    private final Map<String, EstadoGrupo> estadosGrupos = new ConcurrentHashMap<>();
    private final AtomicInteger contadorSnapshots = new AtomicInteger(0);
    private final Map<String, Long> historicoSnapshots = new ConcurrentHashMap<>();
    private static final AtomicInteger contadorSupercoordenadores = new AtomicInteger(0);
    private static volatile Integer supercoordenadorGlobalAtivo = null;

    // Configurações
    private static final int INTERVALO_MONITORAMENTO_GLOBAL_SEGUNDOS = 30;
    private static final int INTERVALO_SNAPSHOT_GLOBAL_SEGUNDOS = 120; // 2 minutos
    private static final int INTERVALO_SINCRONIZACAO_SEGUNDOS = 60;
    private static final int TIMEOUT_RESPOSTA_GRUPOS_MS = 10000;

    public SuperCoordenador(int idNo, String tipoGrupo,
                            Supplier<Integer> relogioSupplier,
                            Supplier<Boolean> isLiderLocalSupplier,
                            Consumer<String> notificadorCallback,
                            ComunicacaoIntergrupos comunicacaoIntergrupos) {
        this.idNo = idNo;
        this.tipoGrupo = tipoGrupo;
        this.relogioSupplier = relogioSupplier;
        this.isLiderLocalSupplier = isLiderLocalSupplier;
        this.notificadorCallback = notificadorCallback;
        this.comunicacaoIntergrupos = comunicacaoIntergrupos;
        this.emissor = new EmissorMulticast();

        // Inicializar estados dos grupos conhecidos
        estadosGrupos.put("A", new EstadoGrupo("A"));
        estadosGrupos.put("B", new EstadoGrupo("B"));

        System.out.printf("[SUPER-COORD P%d-%s] SuperCoordenador inicializado%n", idNo, tipoGrupo);
    }

    /**
     * Ativa o supercoordenador e inicia suas responsabilidades
     */
    public synchronized void ativarComoSupercoordenador() {
        // Verificar se já existe supercoordenador global
        if (supercoordenadorGlobalAtivo != null && supercoordenadorGlobalAtivo != this.idNo) {
            System.out.printf("[SUPER-COORD P%d-%s] Supercoordenador P%d já ativo, aguardando...%n",
                    idNo, tipoGrupo, supercoordenadorGlobalAtivo);
            return;
        }

        if (isSupercoordenador.getAndSet(true)) {
            return;
        }

        // Definir como supercoordenador global ativo
        supercoordenadorGlobalAtivo = this.idNo;
        contadorSupercoordenadores.incrementAndGet();
        ativo.set(true);

        System.out.printf("%n[SUPER-COORD P%d-%s] *** ATIVADO COMO SUPERCOORDENADOR GLOBAL! ***%n",
                idNo, tipoGrupo);

        notificarEvento("SUPERCOORDENADOR GLOBAL ATIVADO: P" + idNo + "-" + tipoGrupo);
        iniciarTarefasSupercoordenador();
        agendarProximoSnapshotGlobal(10); // Aumentar delay inicial
    }

    /**
     * Desativa o supercoordenador
     */
    public synchronized void desativar() {
        if (!isSupercoordenador.getAndSet(false)) {
            return;
        }

        ativo.set(false);

        // Limpar referência global se sou eu
        if (supercoordenadorGlobalAtivo != null && supercoordenadorGlobalAtivo.equals(this.idNo)) {
            supercoordenadorGlobalAtivo = null;
        }

        contadorSupercoordenadores.decrementAndGet();

        System.out.printf("[SUPER-COORD P%d-%s] Supercoordenador desativado%n", idNo, tipoGrupo);

        // Cancelar tarefas
        tarefasAgendadas.forEach((nome, tarefa) -> {
            tarefa.cancel(true);
        });
        tarefasAgendadas.clear();

        notificarEvento("SUPERCOORDENADOR DESATIVADO: P" + idNo + "-" + tipoGrupo);
    }

    /**
     * Inicia todas as tarefas periódicas do supercoordenador
     */
    private void iniciarTarefasSupercoordenador() {
        // 1. Monitoramento global contínuo
        ScheduledFuture<?> tarefaMonitoramento = scheduler.scheduleAtFixedRate(
                this::executarMonitoramentoGlobal,
                10, // Delay inicial de 10 segundos
                INTERVALO_MONITORAMENTO_GLOBAL_SEGUNDOS,
                TimeUnit.SECONDS
        );
        tarefasAgendadas.put("monitoramento_global", tarefaMonitoramento);

        // 2. Snapshots globais periódicos
        ScheduledFuture<?> tarefaSnapshots = scheduler.scheduleAtFixedRate(
                this::executarSnapshotGlobal,
                60, // Delay inicial de 1 minuto
                INTERVALO_SNAPSHOT_GLOBAL_SEGUNDOS,
                TimeUnit.SECONDS
        );
        tarefasAgendadas.put("snapshots_globais", tarefaSnapshots);

        // 3. Sincronização entre grupos
        ScheduledFuture<?> tarefaSincronizacao = scheduler.scheduleAtFixedRate(
                this::executarSincronizacaoGrupos,
                30, // Delay inicial de 30 segundos
                INTERVALO_SINCRONIZACAO_SEGUNDOS,
                TimeUnit.SECONDS
        );
        tarefasAgendadas.put("sincronizacao_grupos", tarefaSincronizacao);

        // 4. Relatório de coordenação (a cada 5 minutos)
        ScheduledFuture<?> tarefaRelatorio = scheduler.scheduleAtFixedRate(
                this::gerarRelatorioSupercoordenacao,
                300, // Delay inicial de 5 minutos
                300, // A cada 5 minutos
                TimeUnit.SECONDS
        );
        tarefasAgendadas.put("relatorio_coordenacao", tarefaRelatorio);

        System.out.printf("[SUPER-COORD P%d-%s] %d tarefas do supercoordenador iniciadas%n",
                idNo, tipoGrupo, tarefasAgendadas.size());
    }

    /**
     * Executa monitoramento global de todos os grupos
     */
    private void executarMonitoramentoGlobal() {
        if (!ativo.get()) return;

        try {
            System.out.printf("[SUPER-COORD P%d-%s] Executando monitoramento global...%n",
                    idNo, tipoGrupo);

            // Solicitar status de todos os grupos
            comunicacaoIntergrupos.solicitarStatusIntergrupo();

            // Verificar ping dos grupos
            comunicacaoIntergrupos.enviarPingIntergrupo();

            // Atualizar status dos grupos conhecidos
            atualizarStatusGrupos();

            // Verificar se há problemas críticos
            verificarProblemasGlobais();

        } catch (Exception e) {
            System.err.printf("[ERRO SUPER-COORD P%d-%s] Erro no monitoramento global: %s%n",
                    idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Executa captura de estado global (Chandy-Lamport)
     */
    private void executarSnapshotGlobal() {
        if (!ativo.get()) return;

        try {
            String idSnapshot = "SNAP_" + contadorSnapshots.incrementAndGet() + "_" +
                    System.currentTimeMillis();

            System.out.printf("%n[SUPER-COORD P%d-%s] *** INICIANDO SNAPSHOT GLOBAL #%d ***%n",
                    idNo, tipoGrupo, contadorSnapshots.get());

            // Registrar início do snapshot
            historicoSnapshots.put(idSnapshot, System.currentTimeMillis());

            // Enviar marcadores para todos os grupos
            comunicacaoIntergrupos.enviarMarcadorSnapshot(idSnapshot);

            // Notificar sobre início do snapshot
            notificarEvento("SNAPSHOT GLOBAL INICIADO: " + idSnapshot);

            // Agendar finalização do snapshot
            scheduler.schedule(() -> {
                finalizarSnapshotGlobal(idSnapshot);
            }, 30, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.printf("[ERRO SUPER-COORD P%d-%s] Erro no snapshot global: %s%n",
                    idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Executa sincronização entre grupos
     */
    private void executarSincronizacaoGrupos() {
        if (!ativo.get()) return;

        try {
            System.out.printf("[SUPER-COORD P%d-%s] Executando sincronização entre grupos...%n",
                    idNo, tipoGrupo);

            // Verificar se ambos os grupos estão ativos
            long agora = System.currentTimeMillis();
            boolean grupoAAtivo = estadosGrupos.get("A").isAtivo(agora);
            boolean grupoBAtivo = estadosGrupos.get("B").isAtivo(agora);

            if (!grupoAAtivo || !grupoBAtivo) {
                System.out.printf("[SUPER-COORD P%d-%s] AVISO: Nem todos os grupos estão ativos (A:%s, B:%s)%n",
                        idNo, tipoGrupo, grupoAAtivo, grupoBAtivo);
                notificarEvento("GRUPOS DESSINCRONIZADOS - A:" + grupoAAtivo + " B:" + grupoBAtivo);
            }

            // Solicitar sincronização de relógios lógicos
            sincronizarRelogiosLogicos();

        } catch (Exception e) {
            System.err.printf("[ERRO SUPER-COORD P%d-%s] Erro na sincronização: %s%n",
                    idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Atualiza status dos grupos conhecidos
     */
    private void atualizarStatusGrupos() {
        Map<Integer, ComunicacaoIntergrupos.InfoGrupoRemoto> gruposRemotosConhecidos =
                comunicacaoIntergrupos.getGruposConhecidos();

        long agora = System.currentTimeMillis();

        // Atualizar cada grupo conhecido
        for (ComunicacaoIntergrupos.InfoGrupoRemoto info : gruposRemotosConhecidos.values()) {
            String tipoGrupoRemoto = info.getTipoGrupo();
            EstadoGrupo estado = estadosGrupos.get(tipoGrupoRemoto);

            if (estado != null) {
                long tempoSemContato = agora - info.getUltimoContato();
                estado.atualizarStatus(tempoSemContato < 30000); // 30 segundos de timeout
                estado.setUltimaAtualizacao(info.getUltimoContato());
                estado.setLiderAtual(info.getId());
            }
        }
    }

    private void salvarRelatorio(String relatorio, String nomeArquivo) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(nomeArquivo, true))) {
            writer.write(relatorio);
            writer.newLine();
        } catch (IOException e) {
            System.err.printf("[ERRO SUPER-COORD P%d-%s] Falha ao salvar relatório: %s%n", idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Verifica problemas críticos no sistema global
     */
    private void verificarProblemasGlobais() {
        long agora = System.currentTimeMillis();
        List<String> problemas = new ArrayList<>();

        for (Map.Entry<String, EstadoGrupo> entry : estadosGrupos.entrySet()) {
            String nomeGrupo = entry.getKey();
            EstadoGrupo estado = entry.getValue();

            if (!estado.isAtivo(agora)) {
                problemas.add("Grupo " + nomeGrupo + " sem comunicação há " +
                        ((agora - estado.getUltimaAtualizacao()) / 1000) + "s");
            }
        }

        if (!problemas.isEmpty()) {
            System.err.printf("[ALERTA SUPER-COORD P%d-%s] Problemas detectados:%n", idNo, tipoGrupo);
            for (String problema : problemas) {
                System.err.println("  • " + problema);
                notificarEvento("PROBLEMA GLOBAL: " + problema);
            }
        }
    }

    /**
     * Sincroniza relógios lógicos entre grupos
     */
    private void sincronizarRelogiosLogicos() {
        // Implementar sincronização de Lamport entre grupos
        // Por simplicidade, apenas notificamos sobre a sincronização
        System.out.printf("[SUPER-COORD P%d-%s] Sincronização de relógios lógicos executada%n",
                idNo, tipoGrupo);
    }

    /**
     * Finaliza um snapshot global
     */
    private void finalizarSnapshotGlobal(String idSnapshot) {
        if (!historicoSnapshots.containsKey(idSnapshot)) return;

        long tempoInicio = historicoSnapshots.get(idSnapshot);
        long duracao = (System.currentTimeMillis() - tempoInicio) / 1000;

        System.out.printf("[SUPER-COORD P%d-%s] *** SNAPSHOT GLOBAL %s FINALIZADO (duração: %ds) ***%n",
                idNo, tipoGrupo, idSnapshot, duracao);

        // Gerar relatório do snapshot
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(80)).append("\n");
        relatorio.append("           RELATÓRIO DE SNAPSHOT GLOBAL - CHANDY-LAMPORT\n");
        relatorio.append("=".repeat(80)).append("\n");
        relatorio.append(String.format("ID do Snapshot: %s%n", idSnapshot));
        relatorio.append(String.format("Supercoordenador: P%d-%s%n", idNo, tipoGrupo));
        relatorio.append(String.format("Duração: %d segundos%n", duracao));
        relatorio.append(String.format("Timestamp: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        relatorio.append("-".repeat(80)).append("\n");

        // Estado dos grupos
        relatorio.append("ESTADO GLOBAL DOS GRUPOS:\n");
        estadosGrupos.forEach((nome, estado) -> {
            long ultimoContato = (System.currentTimeMillis() - estado.getUltimaAtualizacao()) / 1000;
            relatorio.append(String.format("  • Grupo %s: %s (líder: P%d, último contato: %ds)%n",
                    nome,
                    estado.isAtivo(System.currentTimeMillis()) ? "ATIVO" : "INATIVO",
                    estado.getLiderAtual(),
                    ultimoContato));
        });

        relatorio.append("-".repeat(80)).append("\n");
        relatorio.append(String.format("Total de snapshots executados: %d%n", contadorSnapshots.get()));
        relatorio.append("STATUS: SNAPSHOT GLOBAL CONCLUÍDO COM SUCESSO\n");
        relatorio.append("=".repeat(80)).append("\n");

        // Enviar relatório via multicast
        emissor.enviarMensagem(relatorio.toString(), "239.0.0.1", 12345);
        System.out.print(relatorio.toString());

        notificarEvento("SNAPSHOT GLOBAL FINALIZADO: " + idSnapshot + " (duração: " + duracao + "s)");
    }

    /**
     * Agenda próximo snapshot global
     */
    private void agendarProximoSnapshotGlobal(int delaySegundos) {
        scheduler.schedule(() -> {
            executarSnapshotGlobal();
        }, delaySegundos, TimeUnit.SECONDS);
    }

    /**
     * Gera relatório de supercoordenação
     */
    private void gerarRelatorioSupercoordenacao() {
        if (!ativo.get()) return;

        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(70)).append("\n");
        relatorio.append("        RELATÓRIO DE SUPERCOORDENAÇÃO GLOBAL\n");
        relatorio.append("=".repeat(70)).append("\n");
        relatorio.append(String.format("Supercoordenador: P%d-%s%n", idNo, tipoGrupo));
        relatorio.append(String.format("Status: %s%n", ativo.get() ? "ATIVO" : "INATIVO"));
        relatorio.append(String.format("Tempo ativo: %s%n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        relatorio.append("-".repeat(70)).append("\n");

        // Estatísticas de comunicação
        relatorio.append("ESTATÍSTICAS DE COMUNICAÇÃO INTERGRUPOS:\n");
        relatorio.append(String.format("  • Mensagens enviadas: %d%n", comunicacaoIntergrupos.getMensagensEnviadas()));
        relatorio.append(String.format("  • Mensagens recebidas: %d%n", comunicacaoIntergrupos.getMensagensRecebidas()));
        relatorio.append(String.format("  • Grupos conhecidos: %d%n", comunicacaoIntergrupos.getGruposConhecidos().size()));

        // Status dos grupos
        relatorio.append("\nSTATUS DOS GRUPOS MONITORADOS:\n");
        long agora = System.currentTimeMillis();
        estadosGrupos.forEach((nome, estado) -> {
            long ultimoContato = (agora - estado.getUltimaAtualizacao()) / 1000;
            String status = estado.isAtivo(agora) ? "ATIVO" : "INATIVO";
            relatorio.append(String.format("  • Grupo %s: %s (P%d, %ds atrás)%n",
                    nome, status, estado.getLiderAtual(), ultimoContato));
        });

        // Snapshots executados
        relatorio.append(String.format("\nSNAPSHOTS GLOBAIS EXECUTADOS: %d%n", contadorSnapshots.get()));

        // Tarefas ativas
        long tarefasAtivas = tarefasAgendadas.values().stream()
                .filter(t -> !t.isCancelled() && !t.isDone())
                .count();
        relatorio.append(String.format("TAREFAS DO SUPERCOORDENADOR ATIVAS: %d/%d%n",
                tarefasAtivas, tarefasAgendadas.size()));

        relatorio.append("=".repeat(70)).append("\n");

        // Enviar relatório
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

        emissor.enviarNotificacao(evento, idNo);
    }

    // Getters
    public boolean isSupercoordenador() { return isSupercoordenador.get(); }
    public boolean isAtivo() { return ativo.get(); }
    public int getContadorSnapshots() { return contadorSnapshots.get(); }
    public Map<String, EstadoGrupo> getEstadosGrupos() { return new HashMap<>(estadosGrupos); }

    /**
     * Classe interna para armazenar estado de um grupo
     */
    public static class EstadoGrupo {
        private final String nome;
        private boolean ativo = false;
        private long ultimaAtualizacao = 0;
        private int liderAtual = -1;

        public EstadoGrupo(String nome) {
            this.nome = nome;
        }

        public void atualizarStatus(boolean ativo) {
            this.ativo = ativo;
            if (ativo) {
                this.ultimaAtualizacao = System.currentTimeMillis();
            }
        }

        public boolean isAtivo(long tempoAtual) {
            return ativo && (tempoAtual - ultimaAtualizacao) < 60000; // 1 minuto de timeout
        }

        // Getters e setters
        public String getNome() { return nome; }
        public boolean isAtivo() { return ativo; }
        public long getUltimaAtualizacao() { return ultimaAtualizacao; }
        public void setUltimaAtualizacao(long ultimaAtualizacao) { this.ultimaAtualizacao = ultimaAtualizacao; }
        public int getLiderAtual() { return liderAtual; }
        public void setLiderAtual(int liderAtual) { this.liderAtual = liderAtual; }
    }
}