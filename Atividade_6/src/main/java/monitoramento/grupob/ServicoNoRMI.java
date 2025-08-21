package monitoramento.grupob;

import monitoramento.comum.Recurso;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ServicoNoRMI extends Remote {
    Recurso obterStatus(int relogioRemetente) throws RemoteException;
    void receberMensagemEleicaoAnel(String mensagem, int relogioRemetente) throws RemoteException;
}