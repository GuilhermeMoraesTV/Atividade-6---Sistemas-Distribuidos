package monitoramento.grupob;

import monitoramento.autenticacao.ServidorAutenticacao;
import monitoramento.comum.*;
import monitoramento.coordenacao.EmissorMulticast;
import monitoramento.coordenacao.OuvinteMulticast;
import java.net.ServerSocket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final String ENDERECO_LIDERES = "239.0.0.2";
    private static final int PORTA_LIDERES = 12346;
    private volatile Integer superCoordenadorId = null;
    private final List<Integer> candidatosSuperCoordenador = new CopyOnWriteArrayList<>();
    private OuvinteMulticast ouvinteLideres;
    private AtomicBoolean capturaAtiva = new AtomicBoolean(false);
    private final Map<Integer, Boolean> marcadoresRecebidos = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> mensagensCanal = new ConcurrentHashMap<>();
    private int estadoLocalSnapshot;

    public NoGrupoB(int id, List<Integer> todosPidsDoGrupo, Map<Integer, Integer> portasHeartbeat) throws RemoteException {
        this.id = id;
        this.todosPidsDoGrupo = todosPidsDoGrupo;
        this.portaHeartbeat = portasHeartbeat.get(id);
        this.coordenadorId = todosPidsDoGrupo.stream().max(Integer::compareTo).orElse(this.id);
        for (int pid : todosPidsDoGrupo) {
            nosDaRede.put(pid, new InfoNo(pid, portasHeartbeat.get(pid)));
        }
        List<Integer> pidsOrdenados = todosPidsDoGrupo.stream().sorted().collect(Collectors.toList());
        int meuIndice = pidsOrdenados.indexOf(id);
        this.idProximoNo = pidsOrdenados.get((meuIndice + 1) % pidsOrdenados.size());
        this.servidorRMI = new ServidorRMIImpl(this);
        System.out.printf("[GRUPO B - RMI] Nó %d iniciado. Próximo no anel: P%d.%n", id, idProximoNo);
        iniciarServicosHeartbeat();
        iniciarTarefaCoordenador();
    }

    // CORRIGIDO: Chamadas aos construtores com lambdas explícitos
    private void iniciarServicosHeartbeat() {
        new Thread(new ServidorHeartbeat(() -> this.id, () -> this.ativo.get(), this.portaHeartbeat, (socket) -> this.servidorSocketHeartbeat = socket)).start();
        new Thread(new GestorHeartbeat(() -> this.id, () -> this.coordenadorId, () -> this.nosDaRede, () -> this.iniciarEleicaoAnel())).start();
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

    public void iniciarEleicaoAnel() {
        if (emEleicao.getAndSet(true)) return;
        String mensagemInicial = "ELEICAO:" + this.id + ":" + this.id;
        enviarParaProximo(mensagemInicial);
    }

    private void enviarParaProximo(String mensagem) {
        relogioLamport.incrementAndGet();
        try {
            Registry registry = LocateRegistry.getRegistry("127.0.0.1", 1099);
            ServicoNoRMI stub = (ServicoNoRMI) registry.lookup("NoRMI" + idProximoNo);
            stub.receberMensagemEleicaoAnel(mensagem, this.relogioLamport.get());
        } catch (Exception e) {
            emEleicao.set(false);
        }
    }

    private void iniciarEleicaoSuperCoordenador() {
        System.out.printf("[SUPER-ELEICAO P%d] Tornei-me líder do Grupo B. Iniciando eleição para supercoordenador...%n", id);
        candidatosSuperCoordenador.clear();
        candidatosSuperCoordenador.add(this.id);
        this.ouvinteLideres = new OuvinteMulticast(PORTA_LIDERES, ENDERECO_LIDERES, this::processarMensagemLideres);
        new Thread(this.ouvinteLideres).start();
        EmissorMulticast emissorCoord = new EmissorMulticast();
        emissorCoord.enviarMensagem("CANDIDATO:" + this.id, ENDERECO_LIDERES, PORTA_LIDERES);
        new Thread(() -> {
            try {
                Thread.sleep(8000);
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

    private void pararServicos() {
        try {
            if (servidorSocketHeartbeat != null && !servidorSocketHeartbeat.isClosed()) servidorSocketHeartbeat.close();
            if (servidorSocketAuth != null && !servidorSocketAuth.isClosed()) servidorSocketAuth.close();
            if (ouvinteLideres != null) ouvinteLideres.parar();
        } catch (Exception e) {}
    }

    public void setAtivo(boolean status) {
        this.ativo.set(status);
        if (!status) {
            pararServicos();
        }
    }

    private void coletarEstadoGlobal() {
        relogioLamport.incrementAndGet();
        List<Recurso> snapshot = new ArrayList<>();
        snapshot.add(this.obterStatusLocal());
        for (int pid : todosPidsDoGrupo) {
            if (pid != this.id && nosDaRede.get(pid).isAtivo()) {
                try {
                    Registry registry = LocateRegistry.getRegistry("127.0.0.1", 1099);
                    ServicoNoRMI stub = (ServicoNoRMI) registry.lookup("NoRMI" + pid);
                    Recurso recursoRemoto = stub.obterStatus(this.relogioLamport.get());
                    snapshot.add(recursoRemoto);
                } catch (Exception e) {}
            }
        }

        if (clienteAutenticadoPresente.get()) {
            emissor.enviarRelatorio(this.id, snapshot);
        }
    }

    public void registrarClienteAutenticado() { this.clienteAutenticadoPresente.set(true); }
    public int getId() { return id; }
    public boolean isAtivo() { return ativo.get(); }
    public Map<Integer, InfoNo> getNosDaRede() { return nosDaRede; }
    public ServicoNoRMI getServidorRMI() { return servidorRMI; }
    public int getCoordenadorId() { return coordenadorId; }
    public Recurso obterStatusLocal() {
        if (!ativo.get()) return null;
        return new Recurso(id, relogioLamport.incrementAndGet());
    }

    private class ServidorRMIImpl extends UnicastRemoteObject implements ServicoNoRMI {
        private final NoGrupoB noPai;
        public ServidorRMIImpl(NoGrupoB noPai) throws RemoteException { super(); this.noPai = noPai; }

        @Override
        public Recurso obterStatus(int relogioRemetente) throws RemoteException {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), relogioRemetente) + 1);
            return noPai.obterStatusLocal();
        }

        @Override
        public void receberMensagemEleicaoAnel(String mensagem, int relogioRemetente) throws RemoteException {
            noPai.relogioLamport.set(Math.max(noPai.relogioLamport.get(), relogioRemetente) + 1);
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