package monitoramento.grupob;

import monitoramento.autenticacao.ServidorAutenticacao;
import monitoramento.comum.*;
import monitoramento.coordenacao.EmissorMulticast;
import monitoramento.coordenacao.OuvinteMulticast;
import monitoramento.coordenacao.SuperCoordenador;
import monitoramento.intergrupo.ComunicacaoIntergrupos;
import java.net.ServerSocket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NoGrupoB {
    private final int id;
    private final AtomicBoolean ativo = new AtomicBoolean(true);
    private final AtomicInteger relogioLamport = new AtomicInteger(0);
    private int coordenadorId;
    private final List<Integer> todosPidsDoGrupo;
    private final Map<Integer, InfoNo> nosDaRede = new ConcurrentHashMap<>();
    private final ServicoNoRMI servidorRMI;
    private final int portaHeartbeat;
    private final EmissorMulticast emissor = new EmissorMulticast();
    private final AtomicBoolean clienteAutenticadoPresente = new AtomicBoolean(false);
    private ServerSocket servidorSocketAuth;
    private ServerSocket servidorSocketHeartbeat;
    private final int idProximoNo;
    private AtomicBoolean emEleicao = new AtomicBoolean(false);

    // Super-coordenador
    private static final String ENDERECO_LIDERES = "239.0.0.2";
    private static final int PORTA_LIDERES = 12346;
    private volatile Integer superCoordenadorId = null;
    private final List<Integer> candidatosSuperCoordenador = new CopyOnWriteArrayList<>();
    private OuvinteMulticast ouvinteLideres;

    // NOVOS COMPONENTES - Comunicação Intergrupos e SuperCoordenador
    private final ComunicacaoIntergrupos comunicacaoIntergrupos;
    private final SuperCoordenador superCoordenador;

    // Sistemas integrados
    private final GestorSnapshot gestorSnapshot;
    private final GestorRecuperacao gestorRecuperacao;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public NoGrupoB(int id, List<Integer> todosPidsDoGrupo, Map<Integer, Integer> portasHeartbeat) throws RemoteException {
        this.id = id;
        this.todosPidsDoGrupo = todosPidsDoGrupo;
        this.portaHeartbeat = portasHeartbeat.get(id);
        this.coordenadorId = todosPidsDoGrupo.stream().max(Integer::compareTo).orElse(this.id);

        // Inicializar nós da rede
        for (int pid : todosPidsDoGrupo) {
            nosDaRede.put(pid, new InfoNo(pid, portasHeartbeat.get(pid)));
        }

        // Configurar anel
        List<Integer> pidsOrdenados = todosPidsDoGrupo.stream().sorted().collect(Collectors.toList());
        int meuIndice = pidsOrdenados.indexOf(id);
        this.idProximoNo = pidsOrdenados.get((meuIndice + 1) % pidsOrdenados.size());

        // NOVO: Inicializar comunicação intergrupos
        this.comunicacaoIntergrupos = new ComunicacaoIntergrupos(
                id, "B",
                () -> this.relogioLamport.get(),
                () -> this.id == this.coordenadorId,
                this::processarMensagemIntergrupos,
                () -> new Recurso(this.id, this.relogioLamport.get())
        );

        // NOVO: Inicializar supercoordenador
        this.superCoordenador = new SuperCoordenador(
                id, "B",
                () -> this.relogioLamport.get(),
                () -> this.id == this.coordenadorId,
                this::notificarEvento,
                this.comunicacaoIntergrupos
        );

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

        // Inicializar servidor RMI
        this.servidorRMI = new ServidorRMIImpl(this);

        System.out.printf("[GRUPO B - RMI] Nó %d iniciado. Próximo no anel: P%d.%n", id, idProximoNo);

        iniciarServicosHeartbeat();
        iniciarTarefaCoordenador();
        iniciarMonitoramentoPeriodico();

        // NOVO: Iniciar descoberta de outros grupos
        iniciarDescobertaIntergrupos();
    }

    /**
     * NOVO: Inicia descoberta e comunicação com outros grupos
     */
    private void iniciarDescobertaIntergrupos() {
        // Enviar ping inicial para descobrir outros grupos após 5 segundos
        scheduler.schedule(() -> {
            comunicacaoIntergrupos.enviarPingIntergrupo();
            System.out.printf("[INTERGRUPOS P%d-B] Descoberta de grupos iniciada%n", id);
        }, 5, TimeUnit.SECONDS);

        // Ping periódico para manter comunicação viva
        scheduler.scheduleAtFixedRate(() -> {
            if (id == coordenadorId) { // Apenas o líder faz ping
                comunicacaoIntergrupos.enviarPingIntergrupo();
            }
        }, 30, 60, TimeUnit.SECONDS); // A cada 1 minuto após 30s iniciais
    }

    /**
     * NOVO: Processa mensagens recebidas via comunicação intergrupos
     */
    private void processarMensagemIntergrupos(String mensagem) {
        // Atualizar relógio de Lamport
        relogioLamport.incrementAndGet();

        if (mensagem.startsWith("SNAPSHOT_MARKER:")) {
            // Mensagem de snapshot de outro grupo
            String payload = mensagem.substring(16);
            System.out.printf("[SNAPSHOT P%d-B] Marcador de snapshot intergrupos recebido: %s%n",
                    id, payload);

            if (gestorSnapshot != null) {
                // Processar marcador de snapshot cross-group
                gestorSnapshot.receberMarcador(-1, relogioLamport.get(), payload);
            }
        } else if (mensagem.startsWith("SUPER_ELECTION:")) {
            // Mensagem de eleição de supercoordenador
            String payload = mensagem.substring(15);
            System.out.printf("[ELEIÇÃO SUPER P%d-B] Candidatura intergrupos: %s%n", id, payload);

            // Extrair informações do candidato
            if (payload.contains("candidato:") && payload.contains("prioridade:")) {
                try {
                    String[] partes = payload.split(",");
                    for (String parte : partes) {
                        if (parte.startsWith("candidato:")) {
                            String candidatoInfo = parte.substring(10);
                            // Processar candidatura na eleição global
                            processarCandidaturaGlobal(candidatoInfo);
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("[ERRO P%d-B] Erro ao processar candidatura: %s%n", id, e.getMessage());
                }
            }
        }
    }

    /**
     * NOVO: Processa candidaturas para supercoordenador global
     */
    private void processarCandidaturaGlobal(String candidatoInfo) {
        // candidatoInfo formato: "idNo-tipoGrupo"
        String[] partes = candidatoInfo.split("-");
        if (partes.length != 2) return;

        try {
            int idCandidato = Integer.parseInt(partes[0]);
            String grupoCandidato = partes[1];

            System.out.printf("[ELEIÇÃO SUPER P%d-B] Candidato: P%d-%s%n", id, idCandidato, grupoCandidato);

            // Se eu sou líder do meu grupo, também sou candidato
            if (id == coordenadorId) {
                candidatosSuperCoordenador.add(id); // Minha candidatura
            }

            // Adicionar candidato externo se não estiver na lista
            if (!candidatosSuperCoordenador.contains(idCandidato)) {
                candidatosSuperCoordenador.add(idCandidato);
            }

        } catch (NumberFormatException e) {
            System.err.printf("[ERRO P%d-B] ID de candidato inválido: %s%n", id, candidatoInfo);
        }
    }

    private void iniciarServicosHeartbeat() {
        new Thread(new ServidorHeartbeat(
                () -> this.id,
                () -> this.ativo.get(),
                this.portaHeartbeat,
                (s) -> this.servidorSocketHeartbeat = s
        )).start();

        // Aguardar inicialização antes de iniciar gestor de heartbeat
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Aguardar 5 segundos
                new GestorHeartbeat(
                        () -> this.id,
                        () -> this.coordenadorId,
                        () -> this.nosDaRede,
                        this::iniciarEleicaoSuperCoordenador
                ).run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
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

                        // NOVO: Solicitar status de outros grupos
                        comunicacaoIntergrupos.solicitarStatusIntergrupo();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    /**
     * NOVO: Inicia monitoramento periódico e relatórios do sistema
     */
    private void iniciarMonitoramentoPeriodico() {
        // Relatório de recuperação a cada 2 minutos
        scheduler.scheduleAtFixedRate(() -> {
            if (id == coordenadorId && ativo.get()) {
                gestorRecuperacao.gerarRelatorioRecuperacao();
            }
        }, 120, 120, TimeUnit.SECONDS);

        // MODIFICADO: Snapshot periódico apenas se for supercoordenador
        scheduler.scheduleAtFixedRate(() -> {
            if (superCoordenador.isSupercoordenador() && ativo.get()) {
                System.out.printf("[SNAPSHOT P%d-B] Iniciando snapshot como supercoordenador%n", id);
                gestorSnapshot.iniciarCapturaEstado();
            }
        }, 300, 300, TimeUnit.SECONDS);

        // NOVO: Relatório de comunicação intergrupos a cada 3 minutos
        scheduler.scheduleAtFixedRate(() -> {
            if (id == coordenadorId && ativo.get()) {
                String relatorio = comunicacaoIntergrupos.gerarRelatorioIntergrupos();
                System.out.print(relatorio);
                emissor.enviarMensagem(relatorio, "239.0.0.1", 12345);
            }
        }, 180, 180, TimeUnit.SECONDS);
    }

    public void iniciarEleicaoAnel() {
        if (emEleicao.getAndSet(true)) return;

        System.out.printf("[ANEL P%d] Iniciando eleição em anel%n", id);
        notificarEvento("ELEIÇÃO EM ANEL INICIADA POR P" + id);

        String mensagemInicial = "ELEICAO:" + this.id + ":" + this.id;
        enviarParaProximo(mensagemInicial);
    }

    private void enviarParaProximo(String mensagem) {
        relogioLamport.incrementAndGet();

        // Encontrar próximo nó ativo no anel
        int proximoAtivo = encontrarProximoNoAtivo();

        if (proximoAtivo == -1) {
            // Se não há próximo ativo, eu me torno líder
            System.out.printf("[ANEL P%d] Nenhum próximo nó ativo, assumindo liderança%n", id);
            synchronized (this) {
                this.coordenadorId = this.id;
                this.emEleicao.set(false);
            }
            notificarEvento("NOVO LÍDER ELEITO NO GRUPO B: P" + id);
            iniciarEleicaoSuperCoordenador();
            return;
        }

        try {
            Registry registry = LocateRegistry.getRegistry("127.0.0.1", 1099);
            ServicoNoRMI stub = (ServicoNoRMI) registry.lookup("NoRMI" + proximoAtivo);
            stub.receberMensagemEleicaoAnel(mensagem, this.relogioLamport.get());
        } catch (Exception e) {
            System.err.printf("[ERRO P%d-B] Falha ao enviar para P%d: %s%n", id, proximoAtivo, e.getMessage());

            // Marcar próximo nó como falho e tentar novamente
            InfoNo noProximo = nosDaRede.get(proximoAtivo);
            if (noProximo != null) {
                noProximo.setAtivo(false);
                gestorRecuperacao.registrarFalha(proximoAtivo);
            }

            // Tentar enviar para o próximo nó no anel
            enviarParaProximo(mensagem);
        }
    }

    // Metodo auxiliar:
    private int encontrarProximoNoAtivo() {
        List<Integer> pidsOrdenados = todosPidsDoGrupo.stream()
                .sorted()
                .collect(Collectors.toList());

        int meuIndice = pidsOrdenados.indexOf(id);

        // Procurar próximo nó ativo no anel
        for (int i = 1; i < pidsOrdenados.size(); i++) {
            int indiceProximo = (meuIndice + i) % pidsOrdenados.size();
            int idProximo = pidsOrdenados.get(indiceProximo);

            if (idProximo != id && nosDaRede.get(idProximo).isAtivo()) {
                return idProximo;
            }
        }

        return -1; // Nenhum próximo nó ativo encontrado
    }

    // Metodo para finalizar eleição:
    private void finalizarEleicaoComoLider() {
        synchronized (this) {
            this.coordenadorId = this.id;
            this.emEleicao.set(false);
        }

        System.out.printf("[ANEL P%d] *** EU SOU O NOVO LÍDER DO GRUPO B! ***%n", id);
        notificarEvento("NOVO LÍDER ELEITO NO GRUPO B: P" + id);

        // Iniciar eleição de supercoordenador após delay
        scheduler.schedule(() -> {
            iniciarEleicaoSuperCoordenador();
        }, 3000, TimeUnit.MILLISECONDS);
    }

    /**
     * MODIFICADO: Eleição de supercoordenador com comunicação intergrupos
     */
    private void iniciarEleicaoSuperCoordenador() {
        System.out.printf("[SUPER-ELEIÇÃO P%d] Tornei-me líder do Grupo B. Iniciando eleição para supercoordenador...%n", id);
        candidatosSuperCoordenador.clear();
        candidatosSuperCoordenador.add(this.id);

        this.ouvinteLideres = new OuvinteMulticast(PORTA_LIDERES, ENDERECO_LIDERES, this::processarMensagemLideres);
        new Thread(this.ouvinteLideres).start();

        // NOVO: Enviar candidatura via comunicação intergrupos também
        comunicacaoIntergrupos.enviarCandidaturaSuper();
        emissor.enviarMensagem("CANDIDATO:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);

        new Thread(() -> {
            try {
                Thread.sleep(10000); // Aumentado para 10 segundos para aguardar outros grupos

                int vencedor = candidatosSuperCoordenador.stream()
                        .max(Integer::compareTo)
                        .orElse(this.id);

                superCoordenadorId = vencedor;
                System.out.printf("[SUPER-ELEIÇÃO P%d] Eleição concluída. O Supercoordenador é P%d.%n",
                        id, superCoordenadorId);

                notificarEvento("SUPERCOORDENADOR ELEITO: P" + superCoordenadorId);

                // NOVO: Se eu sou o supercoordenador, ativar responsabilidades
                if (id == superCoordenadorId) {
                    System.out.printf("[SUPER-COORD P%d-B] *** TORNEI-ME SUPERCOORDENADOR GLOBAL! ***%n", id);
                    superCoordenador.ativarComoSupercoordenador();
                } else {
                    System.out.printf("[SUPER-COORD P%d-B] Supercoordenador é P%d, continuando como líder local%n",
                            id, superCoordenadorId);
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
                    System.out.printf("[ELEIÇÃO SUPER P%d-B] Novo candidato: P%d%n", id, remetenteId);
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
        snapshot.add(this.obterStatusLocal());

        for (int pid : todosPidsDoGrupo) {
            if (pid != this.id && nosDaRede.get(pid).isAtivo()) {
                coletarStatusNo(pid, snapshot);
            }
        }

        if (clienteAutenticadoPresente.get()) {
            emissor.enviarRelatorio(this.id, snapshot);
        }
    }

    /**
     * NOVO: Coleta status de um nó específico com tratamento de falhas
     */
    private void coletarStatusNo(int pid, List<Recurso> snapshot) {
        try {
            Registry registry = LocateRegistry.getRegistry("127.0.0.1", 1099);
            ServicoNoRMI stub = (ServicoNoRMI) registry.lookup("NoRMI" + pid);
            Recurso recursoRemoto = stub.obterStatus(this.relogioLamport.get());

            if (recursoRemoto != null) {
                snapshot.add(recursoRemoto);

                // Confirmar que nó está ativo
                InfoNo infoNo = nosDaRede.get(pid);
                if (!infoNo.isAtivo()) {
                    gestorRecuperacao.registrarRecuperacao(pid);
                }
            }

        } catch (Exception e) {
            InfoNo infoNo = nosDaRede.get(pid);
            if (infoNo.isAtivo()) {
                gestorRecuperacao.registrarFalha(pid);
            }
        }
    }

    /**
     * NOVO: Notifica eventos importantes
     */
    private void notificarEvento(String evento) {
        emissor.enviarNotificacao(evento, this.id);
    }

    /**
     * MODIFICADO: Para todos os serviços incluindo comunicação intergrupos
     */
    public void setAtivo(boolean status) {
        this.ativo.set(status);
        if (!status) {
            System.out.printf("[GRUPO B P%d] Parando todos os serviços...%n", id);

            scheduler.shutdown();

            // NOVO: Parar comunicação intergrupos
            if (comunicacaoIntergrupos != null) {
                comunicacaoIntergrupos.parar();
            }

            // NOVO: Desativar supercoordenador se ativo
            if (superCoordenador != null && superCoordenador.isSupercoordenador()) {
                superCoordenador.desativar();
            }

            pararServicos();

            System.out.printf("[GRUPO B P%d] Todos os serviços foram encerrados%n", id);
        }
    }

    private void pararServicos() {
        try {
            if (servidorSocketHeartbeat != null && !servidorSocketHeartbeat.isClosed()) {
                servidorSocketHeartbeat.close();
            }
            if (servidorSocketAuth != null && !servidorSocketAuth.isClosed()) {
                servidorSocketAuth.close();
            }
            if (ouvinteLideres != null) {
                ouvinteLideres.parar();
            }
        } catch (Exception e) {
            System.err.printf("[ERRO P%d] Erro ao fechar sockets: %s%n", id, e.getMessage());
        }
    }

    // NOVO: Método público para registrar cliente autenticado
    public void registrarClienteAutenticado() {
        this.clienteAutenticadoPresente.set(true);
        notificarEvento("CLIENTE AUTENTICADO COM SUCESSO");
    }

    // Getters existentes
    public int getId() { return id; }
    public boolean isAtivo() { return ativo.get(); }
    public Map<Integer, InfoNo> getNosDaRede() { return nosDaRede; }
    public ServicoNoRMI getServidorRMI() { return servidorRMI; }
    public int getCoordenadorId() { return coordenadorId; }

    // NOVOS Getters
    public ComunicacaoIntergrupos getComunicacaoIntergrupos() { return comunicacaoIntergrupos; }
    public SuperCoordenador getSuperCoordenador() { return superCoordenador; }
    public boolean isSupercoordenador() { return superCoordenador.isSupercoordenador(); }
    public GestorSnapshot getGestorSnapshot() { return gestorSnapshot; }

    public Recurso obterStatusLocal() {
        if (!ativo.get()) return null;
        return new Recurso(id, relogioLamport.incrementAndGet());
    }

    private class ServidorRMIImpl extends UnicastRemoteObject implements ServicoNoRMI {
        private final NoGrupoB noPai;

        public ServidorRMIImpl(NoGrupoB noPai) throws RemoteException {
            super();
            this.noPai = noPai;
        }

        @Override
        public Recurso obterStatus(int relogioRemetente) throws RemoteException {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), relogioRemetente) + 1);
            return noPai.obterStatusLocal();
        }

        @Override
        public void receberMensagemEleicaoAnel(String mensagem, int relogioRemetente) throws RemoteException {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), relogioRemetente) + 1);

            // Registrar mensagem no snapshot se ativo
            if (noPai.gestorSnapshot.isCapturaAtiva()) {
                noPai.gestorSnapshot.registrarMensagemCanal(-1, "ELEICAO_ANEL: " + mensagem);
            }

            String[] partes = mensagem.split(":");
            String tipoMsg = partes[0];
            int idVencedor = Integer.parseInt(partes[1]);

            if (tipoMsg.equals("LIDER")) {
                // Se eu iniciei a mensagem de LIDER e ela voltou para mim, a notificação terminou.
                if (noPai.id == idVencedor) {
                    System.out.printf("[ANEL P%d] Anúncio de líder completou o anel.%n", noPai.id);
                    return;
                }
                // Se eu não sou o líder e a eleição terminou, atualizo meu estado e repasso.
                if (noPai.emEleicao.getAndSet(false)) {
                    noPai.coordenadorId = idVencedor;
                    System.out.printf("[ANEL P%d] Fim da eleição. Novo líder é P%d.%n", noPai.id, idVencedor);

                    noPai.notificarEvento("NOVO LÍDER ELEITO NO GRUPO B: P" + idVencedor);

                    if (noPai.id == noPai.coordenadorId) {
                        noPai.iniciarEleicaoSuperCoordenador();
                    }
                }
                noPai.enviarParaProximo(mensagem); // Sempre repassa a mensagem de LIDER
            } else { // Mensagem de ELEICAO
                String idsVisitados = partes[2];
                // Se a mensagem de eleição já passou por mim, a volta foi completada
                if (idsVisitados.contains(String.valueOf(noPai.id))) {
                    // A primeira volta terminou, agora inicio a segunda volta com o anúncio do LIDER
                    noPai.enviarParaProximo("LIDER:" + idVencedor);
                } else { // Eleição em andamento
                    int novoVencedorParcial = Math.max(noPai.id, idVencedor);
                    String novaMensagem = "ELEICAO:" + novoVencedorParcial + ":" + idsVisitados + "," + noPai.id;
                    noPai.enviarParaProximo(novaMensagem);
                }
            }
        }
    }
}