package monitoramento.grupoa;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import monitoramento.autenticacao.ServidorAutenticacao;
import monitoramento.comum.*;
import monitoramento.coordenacao.EmissorMulticast;
import monitoramento.coordenacao.OuvinteMulticast;
import monitoramento.grpc.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NoGrupoA {
    private final int id;
    private final AtomicBoolean ativo = new AtomicBoolean(true);
    private final AtomicInteger relogioLamport = new AtomicInteger(0);
    private int coordenadorId;
    private final List<Integer> todosPidsDoGrupo;
    private final Map<Integer, InfoNo> nosDaRede = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> portasGrpcDosNos;
    private final int portaHeartbeat;
    private final int portaGrpc;
    private final Server servidorGrpc;
    private final EmissorMulticast emissor = new EmissorMulticast();
    private final AtomicBoolean clienteAutenticadoPresente = new AtomicBoolean(false);
    private ServerSocket servidorSocketAuth;
    private ServerSocket servidorSocketHeartbeat;

    // Eleição Bully
    private AtomicBoolean emEleicao = new AtomicBoolean(false);
    private AtomicBoolean respondeuOk = new AtomicBoolean(false);

    // Super-coordenador
    private static final String ENDERECO_LIDERES = "239.0.0.2";
    private static final int PORTA_LIDERES = 12346;
    private volatile Integer superCoordenadorId = null;
    private final List<Integer> candidatosSuperCoordenador = new CopyOnWriteArrayList<>();
    private OuvinteMulticast ouvinteLideres;

    // Sistemas integrados
    private final GestorSnapshot gestorSnapshot;
    private final GestorRecuperacao gestorRecuperacao;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public NoGrupoA(int id, List<Integer> todosPidsDoGrupo, Map<Integer, Integer> portasHeartbeat,
                    Map<Integer, Integer> portasGrpc) throws IOException {
        this.id = id;
        this.todosPidsDoGrupo = todosPidsDoGrupo;
        this.portaHeartbeat = portasHeartbeat.get(id);
        this.portaGrpc = portasGrpc.get(id);
        this.portasGrpcDosNos = portasGrpc;
        this.coordenadorId = todosPidsDoGrupo.stream().max(Integer::compareTo).orElse(this.id);

        // Inicializar nós da rede
        for (int pid : todosPidsDoGrupo) {
            nosDaRede.put(pid, new InfoNo(pid, portasHeartbeat.get(pid)));
        }

        // Inicializar gestores
        this.gestorSnapshot = new GestorSnapshot(
                () -> this.id,
                () -> this.relogioLamport.get(),
                () -> this.ativo.get()
        );

        this.gestorRecuperacao = new GestorRecuperacao(
                () -> this.id,
                () -> this.nosDaRede,
                this::notificarEvento
        );

        // Inicializar servidor gRPC
        this.servidorGrpc = ServerBuilder.forPort(this.portaGrpc)
                .addService(new ServicoGrupoAImpl(this))
                .build();
        this.servidorGrpc.start();

        System.out.printf("[GRUPO A - gRPC] Nó %d iniciado na porta gRPC %d.%n", id, portaGrpc);

        iniciarServicosHeartbeat();
        iniciarTarefaCoordenador();
        iniciarMonitoramentoPeriodico();
    }

    private void iniciarServicosHeartbeat() {
        new Thread(new ServidorHeartbeat(
                () -> this.id,
                () -> this.ativo.get(),
                this.portaHeartbeat,
                (s) -> this.servidorSocketHeartbeat = s
        )).start();

        new Thread(new GestorHeartbeat(
                () -> this.id,
                () -> this.coordenadorId,
                () -> this.nosDaRede,
                () -> this.iniciarEleicao()
        )).start();
    }

    private void iniciarTarefaCoordenador() {
        new Thread(() -> {
            Thread servidorAuthThread = null;
            while (ativo.get()) {
                try {
                    Thread.sleep(10000);
                    if (id == coordenadorId && ativo.get()) {
                        if (servidorAuthThread == null || !servidorAuthThread.isAlive()) {
                            servidorAuthThread = new Thread(new ServidorAutenticacao(
                                    () -> this.id,
                                    () -> this.coordenadorId,
                                    () -> this.ativo.get(),
                                    () -> this.registrarClienteAutenticado(),
                                    (socket) -> this.servidorSocketAuth = socket
                            ));
                            servidorAuthThread.start();
                        }
                        coletarEstadoGlobal();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    /**
     * Inicia monitoramento periódico e relatórios do sistema
     */
    private void iniciarMonitoramentoPeriodico() {
        // Relatório de recuperação a cada 2 minutos
        scheduler.scheduleAtFixedRate(() -> {
            if (id == coordenadorId && ativo.get()) {
                gestorRecuperacao.gerarRelatorioRecuperacao();
            }
        }, 120, 120, TimeUnit.SECONDS);

        // Snapshot periódico a cada 5 minutos (se for supercoordenador)
        scheduler.scheduleAtFixedRate(() -> {
            if (id == superCoordenadorId && ativo.get()) {
                gestorSnapshot.iniciarCapturaEstado();
            }
        }, 300, 300, TimeUnit.SECONDS);
    }

    public void iniciarEleicao() {
        if (emEleicao.getAndSet(true)) return;

        System.out.printf("[BULLY P%d] Iniciando eleição Bully%n", id);
        this.respondeuOk.set(false);

        List<Integer> pidsMaiores = todosPidsDoGrupo.stream()
                .filter(p -> p > this.id)
                .collect(Collectors.toList());

        boolean algumMaiorContactado = false;
        for (int pidMaior : pidsMaiores) {
            if (nosDaRede.get(pidMaior).isAtivo()) {
                enviarMensagemBully(pidMaior, MensagemBully.Tipo.ELEICAO);
                algumMaiorContactado = true;
            }
        }

        if (!algumMaiorContactado) {
            anunciarCoordenador();
            return;
        }

        // Aguardar resposta OK
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                if (!this.respondeuOk.get()) {
                    anunciarCoordenador();
                } else {
                    emEleicao.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void anunciarCoordenador() {
        synchronized (this) {
            if (this.coordenadorId == this.id) {
                return;
            }
            this.coordenadorId = this.id;
        }

        System.out.printf("%n[BULLY P%d] *** EU SOU O NOVO COORDENADOR! ***%n", id);
        notificarEvento("NOVO LÍDER ELEITO NO GRUPO A: P" + id);

        this.emEleicao.set(false);
        this.respondeuOk.set(false);

        for (int pid : todosPidsDoGrupo) {
            if (pid != this.id && nosDaRede.get(pid).isAtivo()) {
                enviarMensagemBully(pid, MensagemBully.Tipo.COORDENADOR);
            }
        }

        iniciarEleicaoSuperCoordenador();
    }

    private void enviarMensagemBully(int idDestino, MensagemBully.Tipo tipo) {
        relogioLamport.incrementAndGet();
        int portaDestino = portasGrpcDosNos.get(idDestino);
        ManagedChannel canal = ManagedChannelBuilder.forAddress("localhost", portaDestino)
                .usePlaintext().build();

        try {
            ServicoGrupoAGrpc.ServicoGrupoABlockingStub stub =
                    ServicoGrupoAGrpc.newBlockingStub(canal);

            MensagemBully mensagem = MensagemBully.newBuilder()
                    .setTipo(tipo)
                    .setIdRemetente(this.id)
                    .setRelogioLamport(this.relogioLamport.get())
                    .build();

            stub.enviarMensagemBully(mensagem);

        } catch (Exception e) {
            nosDaRede.get(idDestino).setAtivo(false);
            gestorRecuperacao.registrarFalha(idDestino);
        } finally {
            try {
                canal.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void iniciarEleicaoSuperCoordenador() {
        System.out.printf("[SUPER-ELEIÇÃO P%d] Tornei-me líder do Grupo A. Iniciando eleição para supercoordenador...%n", id);
        candidatosSuperCoordenador.clear();
        candidatosSuperCoordenador.add(this.id);

        this.ouvinteLideres = new OuvinteMulticast(PORTA_LIDERES, ENDERECO_LIDERES, this::processarMensagemLideres);
        new Thread(this.ouvinteLideres).start();

        emissor.enviarMensagem("CANDIDATO:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);

        new Thread(() -> {
            try {
                Thread.sleep(8000);
                int vencedor = candidatosSuperCoordenador.stream()
                        .max(Integer::compareTo)
                        .orElse(this.id);

                superCoordenadorId = vencedor;
                System.out.printf("[SUPER-ELEIÇÃO P%d] Eleição concluída. O Supercoordenador é P%d.%n",
                        id, superCoordenadorId);

                notificarEvento("SUPERCOORDENADOR ELEITO: P" + superCoordenadorId);

                if (id == superCoordenadorId) {
                    gestorSnapshot.iniciarCapturaEstado();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void processarMensagemLideres(String mensagem) {
        String[] partes = mensagem.split(":");
        if (partes.length < 2) return;

        String tipo = partes[0];
        int remetenteId = Integer.parseInt(partes[1]);

        // Atualizar relógio de Lamport
        if (partes.length > 2) {
            try {
                int relogioRemoto = Integer.parseInt(partes[2]);
                relogioLamport.set(Math.max(relogioLamport.get(), relogioRemoto) + 1);
            } catch (NumberFormatException e) {
                relogioLamport.incrementAndGet();
            }
        }

        switch (tipo) {
            case "CANDIDATO":
                if (!candidatosSuperCoordenador.contains(remetenteId)) {
                    candidatosSuperCoordenador.add(remetenteId);
                }
                break;

            case "MARCADOR":
                gestorSnapshot.receberMarcador(remetenteId, relogioLamport.get(),
                        java.time.LocalDateTime.now().toString());
                break;

            default:
                // Registrar mensagem no snapshot se ativo
                if (gestorSnapshot.isCapturaAtiva()) {
                    gestorSnapshot.registrarMensagemCanal(remetenteId, mensagem);
                }
                break;
        }
    }

    private void coletarEstadoGlobal() {
        relogioLamport.incrementAndGet();
        List<Recurso> snapshot = new ArrayList<>();
        snapshot.add(new Recurso(this.id, this.relogioLamport.get()));

        for (int pid : todosPidsDoGrupo) {
            if (pid != this.id && nosDaRede.get(pid).isAtivo()) {
                coletarStatusNo(pid, snapshot);
            }
        }

        if (clienteAutenticadoPresente.get()) {
            emissor.enviarRelatorio(this.id, snapshot);
        }
    }

    private void coletarStatusNo(int pid, List<Recurso> snapshot) {
        int portaDestino = portasGrpcDosNos.get(pid);
        ManagedChannel canal = ManagedChannelBuilder.forAddress("localhost", portaDestino)
                .usePlaintext().build();

        try {
            ServicoGrupoAGrpc.ServicoGrupoABlockingStub stub =
                    ServicoGrupoAGrpc.newBlockingStub(canal);

            RequisicaoStatus requisicao = RequisicaoStatus.newBuilder()
                    .setRelogioRemetente(this.relogioLamport.get())
                    .build();

            RespostaStatus resposta = stub.obterStatus(requisicao);
            snapshot.add(new Recurso(pid, resposta.getRelogioNo()));

            // Confirmar que nó está ativo
            InfoNo infoNo = nosDaRede.get(pid);
            if (!infoNo.isAtivo()) {
                gestorRecuperacao.registrarRecuperacao(pid);
            }

        } catch (Exception e) {
            InfoNo infoNo = nosDaRede.get(pid);
            if (infoNo.isAtivo()) {
                gestorRecuperacao.registrarFalha(pid);
            }
        } finally {
            try {
                canal.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void notificarEvento(String evento) {
        emissor.enviarNotificacao(evento, this.id);
    }

    public void setAtivo(boolean status) {
        this.ativo.set(status);
        if (!status) {
            servidorGrpc.shutdown();
            scheduler.shutdown();
            pararServicosSocket();
            if (ouvinteLideres != null) ouvinteLideres.parar();
        }
    }

    private void pararServicosSocket() {
        try {
            if (servidorSocketHeartbeat != null && !servidorSocketHeartbeat.isClosed())
                servidorSocketHeartbeat.close();
            if (servidorSocketAuth != null && !servidorSocketAuth.isClosed())
                servidorSocketAuth.close();
        } catch (Exception e) {
            System.err.printf("[ERRO P%d] Erro ao fechar sockets: %s%n", id, e.getMessage());
        }
    }

    public void registrarClienteAutenticado() {
        this.clienteAutenticadoPresente.set(true);
        notificarEvento("CLIENTE AUTENTICADO COM SUCESSO");
    }

    // Getters
    public int getId() { return id; }
    public boolean isAtivo() { return ativo.get(); }
    public int getCoordenadorId() { return coordenadorId; }
    public Map<Integer, InfoNo> getNosDaRede() { return nosDaRede; }
    public GestorSnapshot getGestorSnapshot() { return gestorSnapshot; }

    private class ServicoGrupoAImpl extends ServicoGrupoAGrpc.ServicoGrupoAImplBase {
        private final NoGrupoA noPai;

        public ServicoGrupoAImpl(NoGrupoA noPai) {
            this.noPai = noPai;
        }

        @Override
        public void enviarMensagemBully(MensagemBully req, StreamObserver<RespostaBully> resObserver) {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), req.getRelogioLamport()) + 1);

            // Registrar mensagem no snapshot se ativo
            if (noPai.gestorSnapshot.isCapturaAtiva()) {
                noPai.gestorSnapshot.registrarMensagemCanal(req.getIdRemetente(),
                        "BULLY_" + req.getTipo().name());
            }

            switch (req.getTipo()) {
                case ELEICAO:
                    if (noPai.id > req.getIdRemetente()) {
                        noPai.enviarMensagemBully(req.getIdRemetente(), MensagemBully.Tipo.OK);
                        noPai.iniciarEleicao();
                    }
                    break;

                case OK:
                    noPai.respondeuOk.set(true);
                    break;

                case COORDENADOR:
                    noPai.coordenadorId = req.getIdRemetente();
                    noPai.emEleicao.set(false);
                    System.out.printf("[BULLY P%d] Reconheço P%d como novo coordenador%n",
                            noPai.id, req.getIdRemetente());
                    break;
            }

            resObserver.onNext(RespostaBully.newBuilder().setStatus("OK").build());
            resObserver.onCompleted();
        }

        @Override
        public void obterStatus(RequisicaoStatus req, StreamObserver<RespostaStatus> resObserver) {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), req.getRelogioRemetente()) + 1);

            Recurso recurso = new Recurso(noPai.id, noPai.relogioLamport.get());
            RespostaStatus resposta = RespostaStatus.newBuilder()
                    .setUsoCpu(recurso.getUsoCpu())
                    .setUsoMemoria(recurso.getUsoMemoria())
                    .setRelogioNo(recurso.getRelogioLamport())
                    .build();

            resObserver.onNext(resposta);
            resObserver.onCompleted();
        }
    }
}