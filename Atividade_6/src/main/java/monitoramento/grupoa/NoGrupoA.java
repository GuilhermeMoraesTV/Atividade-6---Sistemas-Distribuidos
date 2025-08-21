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
    private AtomicBoolean emEleicao = new AtomicBoolean(false);
    private AtomicBoolean respondeuOk = new AtomicBoolean(false);
    private static final String ENDERECO_LIDERES = "239.0.0.2";
    private static final int PORTA_LIDERES = 12346;
    private volatile Integer superCoordenadorId = null;
    private final List<Integer> candidatosSuperCoordenador = new CopyOnWriteArrayList<>();
    private OuvinteMulticast ouvinteLideres;
    private AtomicBoolean capturaAtiva = new AtomicBoolean(false);
    private final Map<Integer, Boolean> marcadoresRecebidos = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> mensagensCanal = new ConcurrentHashMap<>();
    private int estadoLocalSnapshot;

    public NoGrupoA(int id, List<Integer> todosPidsDoGrupo, Map<Integer, Integer> portasHeartbeat, Map<Integer, Integer> portasGrpc) throws IOException {
        this.id = id;
        this.todosPidsDoGrupo = todosPidsDoGrupo;
        this.portaHeartbeat = portasHeartbeat.get(id);
        this.portaGrpc = portasGrpc.get(id);
        this.portasGrpcDosNos = portasGrpc;
        this.coordenadorId = todosPidsDoGrupo.stream().max(Integer::compareTo).orElse(this.id);
        for (int pid : todosPidsDoGrupo) {
            nosDaRede.put(pid, new InfoNo(pid, portasHeartbeat.get(pid)));
        }
        this.servidorGrpc = ServerBuilder.forPort(this.portaGrpc)
                .addService(new ServicoGrupoAImpl(this))
                .build();
        this.servidorGrpc.start();
        System.out.printf("[GRUPO A - gRPC] Nó %d iniciado na porta gRPC %d.%n", id, portaGrpc);
        iniciarServicosHeartbeat();
        iniciarTarefaCoordenador();
    }

    // CORRIGIDO: Chamadas aos construtores com lambdas explícitos
    private void iniciarServicosHeartbeat() {
        new Thread(new ServidorHeartbeat(() -> this.id, () -> this.ativo.get(), this.portaHeartbeat, (s) -> this.servidorSocketHeartbeat = s)).start();
        new Thread(new GestorHeartbeat(() -> this.id, () -> this.coordenadorId, () -> this.nosDaRede, () -> this.iniciarEleicao())).start();
    }

    private void iniciarTarefaCoordenador() {
        new Thread(() -> {
            Thread servidorAuthThread = null;
            while (ativo.get()) {
                try {
                    Thread.sleep(10000);
                    if (id == coordenadorId && ativo.get()) {
                        if (servidorAuthThread == null || !servidorAuthThread.isAlive()) {
                            servidorAuthThread = new Thread(new ServidorAutenticacao(() -> this.id, () -> this.coordenadorId, () -> this.ativo.get(), () -> this.registrarClienteAutenticado(), (socket) -> this.servidorSocketAuth = socket));
                            servidorAuthThread.start();
                        }
                        coletarEstadoGlobal();
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }).start();
    }

    // ... (restante do código do NoGrupoA.java, que já está correto, deve ser mantido aqui)
    public void iniciarEleicao() {
        if (emEleicao.getAndSet(true)) return;
        this.respondeuOk.set(false);
        List<Integer> pidsMaiores = todosPidsDoGrupo.stream().filter(p -> p > this.id).collect(Collectors.toList());
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
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                if (!this.respondeuOk.get()) {
                    anunciarCoordenador();
                } else {
                    emEleicao.set(false);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).start();
    }

    private void anunciarCoordenador() {
        //  Lógica para evitar que múltiplos nós se declarem coordenadores
        synchronized (this) {
            if (this.coordenadorId == this.id) {
                return;
            }
            this.coordenadorId = this.id;
        }

        System.out.printf("%n[BULLY P%d] *** EU SOU O NOVO COORDENADOR! ***%n", id);
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
        ManagedChannel canal = ManagedChannelBuilder.forAddress("localhost", portaDestino).usePlaintext().build();
        try {
            ServicoGrupoAGrpc.ServicoGrupoABlockingStub stub = ServicoGrupoAGrpc.newBlockingStub(canal);
            MensagemBully mensagem = MensagemBully.newBuilder()
                    .setTipo(tipo)
                    .setIdRemetente(this.id)
                    .setRelogioLamport(this.relogioLamport.get())
                    .build();
            stub.enviarMensagemBully(mensagem);
        } catch (Exception e) {
            nosDaRede.get(idDestino).setAtivo(false);
        } finally {
            try {
                canal.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void iniciarEleicaoSuperCoordenador() {
        System.out.printf("[SUPER-ELEICAO P%d] Tornei-me líder do Grupo A. Iniciando eleição para supercoordenador...%n", id);
        candidatosSuperCoordenador.clear();
        candidatosSuperCoordenador.add(this.id); // Adiciona a si mesmo à lista
        this.ouvinteLideres = new OuvinteMulticast(PORTA_LIDERES, ENDERECO_LIDERES, this::processarMensagemLideres);
        new Thread(this.ouvinteLideres).start();

        EmissorMulticast emissorCoord = new EmissorMulticast();
        emissorCoord.enviarMensagem("CANDIDATO:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);

        new Thread(() -> {
            try {
                Thread.sleep(8000); // Tempo de espera aumentado para 8 segundos
                int vencedor = 0;
                for (Integer candidatoId : candidatosSuperCoordenador) {
                    if (candidatoId > vencedor) {
                        vencedor = candidatoId;
                    }
                }
                superCoordenadorId = vencedor;
                System.out.printf("[SUPER-ELEICAO P%d] Eleição concluída. O Supercoordenador é P%d.%n", id, superCoordenadorId);

                if (id == superCoordenadorId) {
                    iniciarCapturaDeEstado();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).start();
    }

    private void processarMensagemLideres(String mensagem) {
        String[] partes = mensagem.split(":");
        if (partes.length < 2) return;
        String tipo = partes[0];
        int remetenteId = Integer.parseInt(partes[1]);
        if (tipo.equals("CANDIDATO")) {
            if (!candidatosSuperCoordenador.contains(remetenteId)) {
                candidatosSuperCoordenador.add(remetenteId);
            }
        } else if (tipo.equals("MARCADOR")) {
            receberMarcador(remetenteId);
        }
    }

    private void iniciarCapturaDeEstado() {
        if (capturaAtiva.getAndSet(true)) return;
        this.estadoLocalSnapshot = this.relogioLamport.get();
        this.marcadoresRecebidos.clear();
        this.mensagensCanal.clear();
        System.out.printf("[SNAPSHOT P%d] Sou o supercoordenador! INICIANDO CAPTURA DE ESTADO GLOBAL. Estado local: %d%n", id, estadoLocalSnapshot);
        EmissorMulticast emissorCoord = new EmissorMulticast();
        emissorCoord.enviarMensagem("MARCADOR:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);
    }

    public void receberMarcador(int idCanal) {
        if (capturaAtiva.compareAndSet(false, true)) {
            this.estadoLocalSnapshot = this.relogioLamport.get();
            System.out.printf("[SNAPSHOT P%d] Recebi meu primeiro marcador (de P%d). Estado local gravado: %d%n", id, idCanal, estadoLocalSnapshot);
            EmissorMulticast emissorCoord = new EmissorMulticast();
            emissorCoord.enviarMensagem("MARCADOR:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);
        }
        marcadoresRecebidos.put(idCanal, true);
    }

    public void setAtivo(boolean status) {
        this.ativo.set(status);
        if (!status) {
            servidorGrpc.shutdown();
            pararServicosSocket();
            if (ouvinteLideres != null) ouvinteLideres.parar();
        }
    }

    private void pararServicosSocket() {
        try {
            if (servidorSocketHeartbeat != null && !servidorSocketHeartbeat.isClosed()) servidorSocketHeartbeat.close();
            if (servidorSocketAuth != null && !servidorSocketAuth.isClosed()) servidorSocketAuth.close();
        } catch (Exception e) {}
    }

    private void coletarEstadoGlobal() {
        relogioLamport.incrementAndGet();
        List<Recurso> snapshot = new ArrayList<>();
        snapshot.add(new Recurso(this.id, this.relogioLamport.get()));
        for (int pid : todosPidsDoGrupo) {
            if (pid != this.id && nosDaRede.get(pid).isAtivo()) {
                int portaDestino = portasGrpcDosNos.get(pid);
                ManagedChannel canal = ManagedChannelBuilder.forAddress("localhost", portaDestino).usePlaintext().build();
                try {
                    ServicoGrupoAGrpc.ServicoGrupoABlockingStub stub = ServicoGrupoAGrpc.newBlockingStub(canal);
                    RequisicaoStatus requisicao = RequisicaoStatus.newBuilder().setRelogioRemetente(this.relogioLamport.get()).build();
                    RespostaStatus resposta = stub.obterStatus(requisicao);
                    snapshot.add(new Recurso(pid, resposta.getRelogioNo()));
                } catch (Exception e) {}
                finally {
                    try {
                        canal.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
        }
        if (clienteAutenticadoPresente.get()) {
            emissor.enviarRelatorio(this.id, snapshot);
        }
    }

    public void registrarClienteAutenticado() { this.clienteAutenticadoPresente.set(true); }
    public int getId() { return id; }
    public boolean isAtivo() { return ativo.get(); }
    public int getCoordenadorId() { return coordenadorId; }
    public Map<Integer, InfoNo> getNosDaRede() { return nosDaRede; }

    private class ServicoGrupoAImpl extends ServicoGrupoAGrpc.ServicoGrupoAImplBase {
        private final NoGrupoA noPai;
        public ServicoGrupoAImpl(NoGrupoA noPai) { this.noPai = noPai; }

        @Override
        public void enviarMensagemBully(MensagemBully req, StreamObserver<RespostaBully> resObserver) {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), req.getRelogioLamport()) + 1);
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