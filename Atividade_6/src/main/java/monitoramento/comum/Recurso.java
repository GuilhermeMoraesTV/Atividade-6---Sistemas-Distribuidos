package monitoramento.comum;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.time.Instant;

public class Recurso implements Serializable {
    private final int noId;
    private final double usoCpu;
    private final double usoMemoria;
    private final long memoriaTotalGB;
    private final long tempoAtividade;
    private final int processadores;
    private final double cargaSistema;
    private final long timestampColeta;
    private final int relogioLamport;

    public Recurso(int noId, int relogioLamport) {
        this.noId = noId;
        this.relogioLamport = relogioLamport;
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        this.usoCpu = Math.max(0, osBean.getCpuLoad() * 100);
        long totalMemoriaBytes = osBean.getTotalMemorySize();
        long memoriaLivreBytes = osBean.getFreeMemorySize();
        this.usoMemoria = (1 - (double) memoriaLivreBytes / totalMemoriaBytes) * 100;
        this.memoriaTotalGB = totalMemoriaBytes / (1024 * 1024 * 1024);
        this.tempoAtividade = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        this.timestampColeta = Instant.now().getEpochSecond();
        this.processadores = osBean.getAvailableProcessors();
        this.cargaSistema = osBean.getSystemLoadAverage();
    }

    public String paraLinhaRelatorio() {
        String cargaCpuFormatada = (cargaSistema < 0) ? "N/A" : String.format("%.2f", cargaSistema);
        return String.format("| P%-3d | %-12s | %-16s | %-11s | %-15s | %-15s",
                noId,
                String.format("%.2f%%", usoCpu),
                String.format("%.2f%% (~%d GB)", usoMemoria, memoriaTotalGB),
                cargaCpuFormatada,
                String.valueOf(processadores),
                String.format("%ds", tempoAtividade)
        );
    }

    @Override
    public String toString() {
        return String.format("Nó %d -> [Relógio: %d] CPU: %.2f%% | Memória: %.2f%%",
                noId, relogioLamport, usoCpu, usoMemoria);
    }

    // --- GETTERS ADICIONADOS ---
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