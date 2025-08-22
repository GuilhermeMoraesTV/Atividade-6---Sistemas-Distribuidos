package monitoramento.comum;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.time.Instant;

// Classe Recurso: representa o estado de recursos de um nó (CPU, memória, etc.)
// Implementa Serializable para permitir envio em rede ou persistência
public class Recurso implements Serializable {
    // Identificador do nó
    private final int noId;

    // Percentual de uso de CPU do nó
    private final double usoCpu;

    // Percentual de uso de memória do nó
    private final double usoMemoria;

    // Quantidade total de memória do nó em GB
    private final long memoriaTotalGB;

    // Tempo de atividade do nó (uptime) em segundos
    private final long tempoAtividade;

    // Número de processadores disponíveis no sistema
    private final int processadores;

    // Carga média do sistema (System Load Average)
    private final double cargaSistema;

    // Timestamp da coleta (em segundos desde época Unix)
    private final long timestampColeta;

    // Relógio lógico de Lamport associado a este recurso
    private final int relogioLamport;

    // Construtor: coleta os dados de uso do sistema no momento da criação do objeto
    public Recurso(int noId, int relogioLamport) {
        this.noId = noId;
        this.relogioLamport = relogioLamport;

        // Obtém informações do sistema operacional via MXBean
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

        // Uso de CPU em porcentagem (0 a 100)
        this.usoCpu = Math.max(0, osBean.getCpuLoad() * 100);

        // Memória total e memória livre em bytes
        long totalMemoriaBytes = osBean.getTotalMemorySize();
        long memoriaLivreBytes = osBean.getFreeMemorySize();

        // Cálculo do uso de memória em porcentagem
        this.usoMemoria = (1 - (double) memoriaLivreBytes / totalMemoriaBytes) * 100;

        // Conversão da memória total para gigabytes
        this.memoriaTotalGB = totalMemoriaBytes / (1024 * 1024 * 1024);

        // Tempo de atividade do sistema (uptime em milissegundos → convertido em segundos)
        this.tempoAtividade = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        // Marca temporal da coleta (Epoch time em segundos)
        this.timestampColeta = Instant.now().getEpochSecond();

        // Número de processadores disponíveis
        this.processadores = osBean.getAvailableProcessors();

        // Carga média do sistema (pode retornar -1 em sistemas não suportados)
        this.cargaSistema = osBean.getSystemLoadAverage();
    }

    // Método para formatar os dados em forma de linha para relatórios
    public String paraLinhaRelatorio() {
        // Caso a carga do sistema seja inválida (-1), retorna "N/A"
        String cargaCpuFormatada = (cargaSistema < 0) ? "N/A" : String.format("%.2f", cargaSistema);

        // Monta a string formatada para exibição em tabelas
        return String.format("| P%-3d | %-12s | %-16s | %-11s | %-15s | %-15s",
                noId,
                String.format("%.2f%%", usoCpu),
                String.format("%.2f%% (~%d GB)", usoMemoria, memoriaTotalGB),
                cargaCpuFormatada,
                String.valueOf(processadores),
                String.format("%ds", tempoAtividade)
        );
    }

    // Representação textual do objeto para debug/logs
    @Override
    public String toString() {
        return String.format("Nó %d -> [Relógio: %d] CPU: %.2f%% | Memória: %.2f%%",
                noId, relogioLamport, usoCpu, usoMemoria);
    }

    // --- GETTERS: permitem acessar os atributos, mas como os campos são finais, não há setters ---
    public int getNoId() { return noId; }
    public double getUsoCpu() { return usoCpu; }
    public double getUsoMemoria() { return usoMemoria; }
    public long getMemoriaTotalGB() { return memoriaTotalGB; }
    public long getTempoAtividade() { return tempoAtividade; }
    public int getProcessadores() { return processadores; }
    public double getCargaSistema() { return cargaSistema; }
    public long getTimestampColeta() { return timestampColeta; }
    public int getRelogioLamport() { return relogioLamport; }
}
