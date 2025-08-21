package monitoramento.intergrupo;

import monitoramento.comum.Recurso;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Classe responsável pela comunicação UDP Multicast entre os grupos A e B
 * Implementa o protocolo de troca de mensagens intergrupos
 */
public class ComunicacaoIntergrupos {
    // Endereços e portas para comunicação intergrupos
    private static final String ENDERECO_INTERGRUPOS = "239.0.0.4";
    private static final int PORTA_INTERGRUPOS = 12348;

    // Configurações
    private static final int TIMEOUT_RESPOSTA_MS = 5000;
    private static final int BUFFER_SIZE = 2048;

    // Estado interno
    private final int idNo;
    private final String tipoGrupo; // "A" ou "B"
    private final AtomicBoolean ativo = new AtomicBoolean(true);
    private final Supplier<Integer> relogioSupplier;
    private final Supplier<Boolean> isLiderSupplier;

    // Callbacks para integração
    private final Consumer<String> callbackMensagemRecebida;
    private final Supplier<Recurso> callbackObterStatus;

    // Controle de comunicação
    private final Map<Integer, InfoGrupoRemoto> gruposConhecidos = new ConcurrentHashMap<>();
    private MulticastSocket socketOuvinte;
    private Thread threadOuvinte;

    // Estatísticas
    private int mensagensEnviadas = 0;
    private int mensagensRecebidas = 0;

    public ComunicacaoIntergrupos(int idNo, String tipoGrupo,
                                  Supplier<Integer> relogioSupplier,
                                  Supplier<Boolean> isLiderSupplier,
                                  Consumer<String> callbackMensagemRecebida,
                                  Supplier<Recurso> callbackObterStatus) {
        this.idNo = idNo;
        this.tipoGrupo = tipoGrupo;
        this.relogioSupplier = relogioSupplier;
        this.isLiderSupplier = isLiderSupplier;
        this.callbackMensagemRecebida = callbackMensagemRecebida;
        this.callbackObterStatus = callbackObterStatus;

        iniciarOuvinte();

        System.out.printf("[INTERGRUPOS P%d-%s] Comunicação intergrupos iniciada%n",
                idNo, tipoGrupo);
    }

    /**
     * Inicia o ouvinte para mensagens multicast intergrupos
     */
    private void iniciarOuvinte() {
        threadOuvinte = new Thread(() -> {
            try {
                socketOuvinte = new MulticastSocket(PORTA_INTERGRUPOS);
                InetAddress grupoMulticast = InetAddress.getByName(ENDERECO_INTERGRUPOS);
                socketOuvinte.joinGroup(grupoMulticast);
                socketOuvinte.setSoTimeout(1000); // Timeout para verificar se ainda está ativo

                byte[] buffer = new byte[BUFFER_SIZE];

                while (ativo.get()) {
                    try {
                        DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                        socketOuvinte.receive(pacote);

                        String mensagem = new String(pacote.getData(), 0, pacote.getLength());
                        processarMensagemRecebida(mensagem);

                    } catch (SocketTimeoutException e) {
                        // Timeout normal, continua o loop
                    } catch (IOException e) {
                        if (ativo.get()) {
                            System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro ao receber mensagem: %s%n",
                                    idNo, tipoGrupo, e.getMessage());
                        }
                    }
                }

            } catch (Exception e) {
                System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro no ouvinte: %s%n",
                        idNo, tipoGrupo, e.getMessage());
            } finally {
                if (socketOuvinte != null && !socketOuvinte.isClosed()) {
                    socketOuvinte.close();
                }
            }
        });

        threadOuvinte.start();
    }

    /**
     * Processa mensagens recebidas via multicast
     */
    private void processarMensagemRecebida(String mensagem) {
        try {
            String[] partes = mensagem.split("\\|");
            if (partes.length < 4) return;

            String tipo = partes[0];
            int idRemetente = Integer.parseInt(partes[1]);
            String grupoRemetente = partes[2];
            int relogioRemetente = Integer.parseInt(partes[3]);
            String payload = partes.length > 4 ? partes[4] : "";

            // Ignorar mensagens do próprio grupo
            if (grupoRemetente.equals(this.tipoGrupo)) {
                return;
            }

            // Atualizar relógio de Lamport
            int relogioAtual = relogioSupplier.get();
            int novoRelogio = Math.max(relogioAtual, relogioRemetente) + 1;
            // Note: O supplier não pode ser usado para set, isso deve ser feito na classe pai

            // Atualizar informações sobre o grupo remoto
            atualizarInfoGrupoRemoto(idRemetente, grupoRemetente);

            mensagensRecebidas++;

            System.out.printf("[INTERGRUPOS P%d-%s] <- %s de P%d-%s (relógio: %d->%d)%n",
                    idNo, tipoGrupo, tipo, idRemetente, grupoRemetente,
                    relogioRemetente, novoRelogio);

            // Processar diferentes tipos de mensagens
            switch (tipo) {
                case "PING_INTERGRUPO":
                    responderPingIntergrupo(idRemetente, grupoRemetente);
                    break;

                case "PONG_INTERGRUPO":
                    processarPongIntergrupo(idRemetente, grupoRemetente);
                    break;

                case "STATUS_REQUEST":
                    responderStatusIntergrupo(idRemetente, grupoRemetente);
                    break;

                case "STATUS_RESPONSE":
                    processarStatusIntergrupo(payload, idRemetente, grupoRemetente);
                    break;

                case "MARCADOR_SNAPSHOT":
                    processarMarcadorSnapshot(idRemetente, grupoRemetente, payload);
                    break;

                case "ELEICAO_SUPER":
                    processarEleicaoSuper(payload, idRemetente, grupoRemetente);
                    break;

                default:
                    System.out.printf("[INTERGRUPOS P%d-%s] Tipo de mensagem desconhecido: %s%n",
                            idNo, tipoGrupo, tipo);
            }

            // Notificar callback se fornecido
            if (callbackMensagemRecebida != null) {
                callbackMensagemRecebida.accept(mensagem);
            }

        } catch (Exception e) {
            System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro ao processar mensagem: %s%n",
                    idNo, tipoGrupo, e.getMessage());
        }
    }

    /**
     * Envia mensagem via UDP Multicast para outros grupos
     */
    private void enviarMensagem(String tipo, String payload) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String mensagem = String.format("%s|%d|%s|%d|%s",
                    tipo, idNo, tipoGrupo,
                    relogioSupplier.get(), payload);

            byte[] dados = mensagem.getBytes("UTF-8");
            InetAddress endereco = InetAddress.getByName(ENDERECO_INTERGRUPOS);
            DatagramPacket pacote = new DatagramPacket(dados, dados.length,
                    endereco, PORTA_INTERGRUPOS);

            socket.send(pacote);
            mensagensEnviadas++;

            System.out.printf("[INTERGRUPOS P%d-%s] -> %s enviado (%d bytes)%n",
                    idNo, tipoGrupo, tipo, dados.length);

        } catch (Exception e) {
            System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro ao enviar %s: %s%n",
                    idNo, tipoGrupo, tipo, e.getMessage());
        }
    }

    /**
     * Envia ping para descobrir outros grupos
     */
    public void enviarPingIntergrupo() {
        enviarMensagem("PING_INTERGRUPO",
                "timestamp:" + System.currentTimeMillis());
    }

    /**
     * Responde a um ping intergrupo
     */
    private void responderPingIntergrupo(int idRemetente, String grupoRemetente) {
        String resposta = String.format("resposta_para:%d-%s,meu_status:%s",
                idRemetente, grupoRemetente,
                isLiderSupplier.get() ? "LIDER" : "MEMBRO");
        enviarMensagem("PONG_INTERGRUPO", resposta);
    }

    /**
     * Processa resposta de ping intergrupo
     */
    private void processarPongIntergrupo(int idRemetente, String grupoRemetente) {
        System.out.printf("[INTERGRUPOS P%d-%s] Grupo %s está ativo (líder: P%d)%n",
                idNo, tipoGrupo, grupoRemetente, idRemetente);
    }

    /**
     * Solicita status de outros grupos
     */
    public void solicitarStatusIntergrupo() {
        if (isLiderSupplier.get()) {
            enviarMensagem("STATUS_REQUEST", "solicitacao_status_global");
        }
    }

    /**
     * Responde com status do grupo
     */
    private void responderStatusIntergrupo(int idRemetente, String grupoRemetente) {
        if (isLiderSupplier.get()) {
            try {
                Recurso status = callbackObterStatus.get();
                String statusPayload = String.format("grupo:%s,cpu:%.2f,memoria:%.2f,timestamp:%s",
                        tipoGrupo, status.getUsoCpu(),
                        status.getUsoMemoria(),
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                enviarMensagem("STATUS_RESPONSE", statusPayload);
            } catch (Exception e) {
                System.err.printf("[ERRO INTERGRUPOS P%d-%s] Erro ao obter status: %s%n",
                        idNo, tipoGrupo, e.getMessage());
            }
        }
    }

    /**
     * Processa status recebido de outros grupos
     */
    private void processarStatusIntergrupo(String payload, int idRemetente, String grupoRemetente) {
        System.out.printf("[INTERGRUPOS P%d-%s] Status do Grupo %s: %s%n",
                idNo, tipoGrupo, grupoRemetente, payload);
    }

    /**
     * Envia marcador de snapshot para outros grupos
     */
    public void enviarMarcadorSnapshot(String idSnapshot) {
        String payload = String.format("snapshot_id:%s,timestamp:%s,iniciador:%d-%s",
                idSnapshot,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                idNo, tipoGrupo);
        enviarMensagem("MARCADOR_SNAPSHOT", payload);
    }

    /**
     * Processa marcador de snapshot de outros grupos
     */
    private void processarMarcadorSnapshot(int idRemetente, String grupoRemetente, String payload) {
        System.out.printf("[SNAPSHOT INTERGRUPOS P%d-%s] Marcador recebido de P%d-%s: %s%n",
                idNo, tipoGrupo, idRemetente, grupoRemetente, payload);

        // Notificar o gestor de snapshot local
        if (callbackMensagemRecebida != null) {
            callbackMensagemRecebida.accept("SNAPSHOT_MARKER:" + payload);
        }
    }

    /**
     * Envia candidatura para eleição de supercoordenador
     */
    public void enviarCandidaturaSuper() {
        if (isLiderSupplier.get()) {
            String payload = String.format("candidato:%d-%s,prioridade:%d,timestamp:%s",
                    idNo, tipoGrupo, idNo,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            enviarMensagem("ELEICAO_SUPER", payload);
        }
    }

    /**
     * Processa mensagens de eleição de supercoordenador
     */
    private void processarEleicaoSuper(String payload, int idRemetente, String grupoRemetente) {
        System.out.printf("[ELEICAO SUPER P%d-%s] Candidato: P%d-%s (%s)%n",
                idNo, tipoGrupo, idRemetente, grupoRemetente, payload);

        // Notificar callback para processar eleição
        if (callbackMensagemRecebida != null) {
            callbackMensagemRecebida.accept("SUPER_ELECTION:" + payload);
        }
    }

    /**
     * Atualiza informações sobre grupos remotos
     */
    private void atualizarInfoGrupoRemoto(int idRemoto, String grupoRemoto) {
        InfoGrupoRemoto info = gruposConhecidos.computeIfAbsent(idRemoto,
                k -> new InfoGrupoRemoto(idRemoto, grupoRemoto));
        info.atualizarUltimoContato();
    }

    /**
     * Gera relatório de comunicação intergrupos
     */
    public String gerarRelatorioIntergrupos() {
        StringBuilder relatorio = new StringBuilder();
        relatorio.append("\n").append("=".repeat(60)).append("\n");
        relatorio.append("     RELATÓRIO DE COMUNICAÇÃO INTERGRUPOS\n");
        relatorio.append("=".repeat(60)).append("\n");
        relatorio.append(String.format("Nó: P%d-%s | Status: %s%n",
                idNo, tipoGrupo,
                isLiderSupplier.get() ? "LÍDER" : "MEMBRO"));
        relatorio.append(String.format("Mensagens Enviadas: %d | Recebidas: %d%n",
                mensagensEnviadas, mensagensRecebidas));
        relatorio.append("-".repeat(60)).append("\n");

        if (gruposConhecidos.isEmpty()) {
            relatorio.append("Nenhum grupo remoto descoberto\n");
        } else {
            relatorio.append("GRUPOS REMOTOS CONHECIDOS:\n");
            gruposConhecidos.forEach((id, info) -> {
                long ultimoContato = (System.currentTimeMillis() - info.getUltimoContato()) / 1000;
                relatorio.append(String.format("  • P%d-%s: último contato há %ds%n",
                        id, info.getTipoGrupo(), ultimoContato));
            });
        }

        relatorio.append("=".repeat(60)).append("\n");
        return relatorio.toString();
    }

    /**
     * Para a comunicação intergrupos
     */
    public void parar() {
        ativo.set(false);

        if (threadOuvinte != null) {
            threadOuvinte.interrupt();
        }

        if (socketOuvinte != null && !socketOuvinte.isClosed()) {
            socketOuvinte.close();
        }

        System.out.printf("[INTERGRUPOS P%d-%s] Comunicação intergrupos encerrada%n",
                idNo, tipoGrupo);
    }

    // Getters para estatísticas
    public int getMensagensEnviadas() { return mensagensEnviadas; }
    public int getMensagensRecebidas() { return mensagensRecebidas; }
    public Map<Integer, InfoGrupoRemoto> getGruposConhecidos() { return new HashMap<>(gruposConhecidos); }

    /**
     * Classe interna para armazenar informações sobre grupos remotos
     */
    public static class InfoGrupoRemoto {
        private final int id;
        private final String tipoGrupo;
        private long ultimoContato;

        public InfoGrupoRemoto(int id, String tipoGrupo) {
            this.id = id;
            this.tipoGrupo = tipoGrupo;
            this.ultimoContato = System.currentTimeMillis();
        }

        public void atualizarUltimoContato() {
            this.ultimoContato = System.currentTimeMillis();
        }

        // Getters
        public int getId() { return id; }
        public String getTipoGrupo() { return tipoGrupo; }
        public long getUltimoContato() { return ultimoContato; }
    }
}