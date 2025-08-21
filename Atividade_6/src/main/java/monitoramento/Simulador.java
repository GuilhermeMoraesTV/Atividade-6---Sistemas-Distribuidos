package monitoramento;

import monitoramento.grupoa.NoGrupoA;
import monitoramento.grupob.NoGrupoB;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Simulador {
    public static void main(String[] args) {
        List<Integer> pidsGrupoA = Arrays.asList(1, 2, 3);
        List<Integer> pidsGrupoB = Arrays.asList(4, 5, 6);
        Map<Integer, Integer> portasHeartbeat = new HashMap<>();
        Map<Integer, Integer> portasGrpc = new HashMap<>();
        for(int pid : pidsGrupoA) {
            portasHeartbeat.put(pid, 1100 + pid);
            portasGrpc.put(pid, 50050 + pid);
        }
        for(int pid : pidsGrupoB) {
            portasHeartbeat.put(pid, 1100 + pid);
        }

        try {
            System.setProperty("java.rmi.server.hostname", "127.0.0.1");
            Registry registry = LocateRegistry.createRegistry(1099);

            List<NoGrupoA> nosGrupoA = new ArrayList<>();
            for (int pid : pidsGrupoA) {
                nosGrupoA.add(new NoGrupoA(pid, pidsGrupoA, portasHeartbeat, portasGrpc));
            }

            List<NoGrupoB> nosGrupoB = new ArrayList<>();
            for (int pid : pidsGrupoB) {
                NoGrupoB no = new NoGrupoB(pid, pidsGrupoB, portasHeartbeat);
                registry.bind("NoRMI" + pid, no.getServidorRMI());
                nosGrupoB.add(no);
            }

            System.out.println("\n--- [SIMULADOR] Simulação Iniciada ---");

            System.out.println("\n--- [SIMULADOR] Aguardando 20s antes de simular a falha do líder do Grupo A (P3)... ---");
            Thread.sleep(20000);
            NoGrupoA noParaFalharA = nosGrupoA.get(2); // Nó 3
            noParaFalharA.setAtivo(false);

            System.out.println("\n--- [SIMULADOR] Aguardando 20s antes de simular a falha do líder do Grupo B (P6)... ---");
            Thread.sleep(20000);
            NoGrupoB noParaFalharB = nosGrupoB.get(2); // Nó 6
            noParaFalharB.setAtivo(false);
            registry.unbind("NoRMI" + noParaFalharB.getId());

            System.out.println("\n--- [SIMULADOR] Simulação continuará por mais 60 segundos... ---");
            Thread.sleep(60000); // Tempo aumentado para 60s
            System.out.println("\n--- [SIMULADOR] Simulação Finalizada ---");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}