package monitoramento.comum;

/**
 * Uma classe simples para armazenar informações sobre outros nós na rede.
 * Mantém o estado de atividade e o contador de falhas de heartbeat.
 */
public class InfoNo {
    private final int id;
    private final int portaHeartbeat;
    private boolean ativo = true;
    private int contadorFalhas = 0;

    public InfoNo(int id, int portaHeartbeat) {
        this.id = id;
        this.portaHeartbeat = portaHeartbeat;
    }

    // --- MÉTODOS CORRIGIDOS ---

    public int getPortaHeartbeat() { // CORRIGIDO: Adicionado "int"
        return portaHeartbeat;
    }

    public boolean isAtivo() { // CORRIGIDO: Adicionado "boolean"
        return ativo;
    }

    public void setAtivo(boolean ativo) { // CORRIGIDO: Adicionado "void"
        this.ativo = ativo;
    }

    public int getContadorFalhas() { // CORRIGIDO: Adicionado "int"
        return contadorFalhas;
    }

    public void incrementarContadorFalhas() { // CORRIGIDO: Adicionado "void"
        this.contadorFalhas++;
    }

    public void resetarContadorFalhas() { // CORRIGIDO: Adicionado "void"
        this.contadorFalhas = 0;
    }
}