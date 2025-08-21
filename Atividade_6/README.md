
# Sistema Distribuído de Monitoramento de Recursos com Tolerância a Falhas

## 1\. Visão Geral

Este projeto é uma aplicação académica desenvolvida para a disciplina de Sistemas Distribuídos, que simula um sistema de monitoramento de recursos (CPU, memória, etc.) em uma rede de servidores. A principal característica do sistema é a sua **tolerância a falhas**: ele é capaz de detetar a queda do nó coordenador (líder), eleger autonomamente um novo líder e continuar a sua operação sem interrupção do serviço para o cliente final.

O sistema demonstra a aplicação prática de múltiplos conceitos fundamentais de sistemas distribuídos, como eleição de líder, deteção de falhas, sincronização de tempo lógico e comunicação em grupo. A simulação principal (`Simulador.java`) inicia um conjunto de nós, permite que o sistema se estabilize e, em seguida, simula a falha do líder para demonstrar a robustez e a capacidade de recuperação do sistema.

## 2\. Funcionalidades Principais

* **Monitoramento de Recursos:** O líder coleta periodicamente o estado de uso de CPU, memória, tempo de atividade e carga do sistema de todos os nós ativos na rede. A classe `Recurso.java` é responsável por obter essas métricas do sistema operacional.

* **Deteção de Falhas por Heartbeat:** Cada nó monitora ativamente os outros através de um mecanismo de "PING-PONG" via Sockets TCP. A classe `HeartbeatGestor` envia "PINGs" periodicamente, e a `HeartbeatServidor` responde com "PONGs". Um nó é considerado inativo após 3 tentativas de comunicação falhadas, o que aciona os mecanismos de recuperação.

* **Eleição de Líder (Algoritmo Bully):** Se a falha do coordenador for detetada, o algoritmo Bully é iniciado automaticamente para eleger o nó de maior ID entre os ativos como o novo líder. O processo é iniciado pelo método `iniciarEleicao` na classe `No.java`, que envia mensagens de eleição para nós com IDs superiores e, caso não receba resposta, autoproclama-se o novo coordenador.

* **Sincronização com Relógio de Lamport:** Para manter uma ordem causal parcial dos eventos, o sistema utiliza Relógios de Lamport. O relógio lógico de um nó, um `AtomicInteger` na classe `No`, é atualizado sempre que uma mensagem RMI é recebida, garantindo que o tempo lógico seja o máximo entre o seu próprio tempo e o do remetente, mais um.

* **Comunicação em Grupo (Multicast):** Os relatórios de estado consolidados são enviados pelo líder para um grupo multicast UDP. A classe `EmissorMulticast.java` formata e envia os relatórios, permitindo que múltiplos clientes (`ClienteMonitor.java`) monitorem o sistema em tempo real de forma eficiente.

* **Acesso por Autenticação:** O acesso aos dados do monitoramento é protegido. Um cliente (`ClienteAutenticado.java`) deve primeiro autenticar-se com o líder através do `ServidorAutenticacao`. Somente após uma autenticação bem-sucedida (com as credenciais "admin;admin"), o líder começa a transmitir os relatórios via multicast.

* **Cliente Resiliente:** A aplicação cliente é capaz de detetar a ausência de relatórios (indicando uma falha do líder) através de um `SocketTimeoutException`. Ao detetar a falha, o cliente tenta re-autenticar-se automaticamente, procurando pelo novo líder eleito para continuar a receber os dados sem interrupções manuais.

## 3\. Tecnologias Utilizadas

* **Linguagem:** Java
* **Comunicação:**
    * **Java RMI (Remote Method Invocation):** Para a chamada de métodos remotos na coleta de estado e na eleição de líder. A interface `ServicoNo` define os métodos remotos.
    * **Sockets TCP/IP:** Para a comunicação do mecanismo de Heartbeat, garantindo uma verificação de atividade fiável.
    * **Sockets UDP Multicast:** Para a disseminação eficiente dos relatórios de monitoramento para múltiplos clientes.

## 4\. Estrutura do Projeto

```
Atividade_5_Sistema_Monitoramento_de_Recursos/
│
├── bin/                    # Ficheiros .class compilados
│   └── monitoramento/
│
├── src/                    # Código fonte do projeto
│   └── monitoramento/
│       ├── No.java         # Classe principal que representa um nó do sistema.
│       ├── Simulador.java  # Classe para iniciar e gerir a simulação.
│       ├── ClienteAutenticado.java # Cliente que se autentica e se reconecta.
│       ├── ClienteMonitor.java # Cliente que ouve os relatórios multicast.
│       ├── HeartbeatGestor.java # Envia PINGs para detetar falhas.
│       ├── HeartbeatServidor.java # Responde PONGs aos gestores.
│       ├── ServidorAutenticacao.java # Lida com a autenticação de clientes.
│       ├── EmissorMulticast.java # Envia relatórios para o grupo multicast.
│       ├── Recurso.java    # Representa os dados de recursos de um nó.
│       ├── NoInfo.java     # Armazena estado e informações de outros nós.
│       └── ServicoNo.java  # Interface RMI para comunicação entre nós.
│
├── COMPILAR.bat            # Script para compilar o projeto.
├── EXECUTAR_NOS.bat        # Script para iniciar a simulação dos nós.
├── EXECUTAR_CLIENTE.bat    # Script para iniciar o cliente multicast simples.
├── EXECUTAR_CLIENTE_AUTENTICADO.bat # Script para iniciar o cliente resiliente.
├── EXECUTAR_TUDO.bat       # Script para compilar e executar tudo.
└── README.md               # Este ficheiro.
```

## 5\. Como Executar

Para executar a simulação, siga os passos abaixo. Os scripts `.bat` foram criados para automatizar o processo no Windows.

### Execução Simplificada

Como alternativa, o script `EXECUTAR_TUDO.bat` automatiza todos os passos abaixo, compilando o projeto e abrindo as janelas necessárias para o simulador e para o cliente.

```bash
EXECUTAR_TUDO.bat
```

### Passo 1: Compilar o Projeto

Execute o script `COMPILAR.bat`. Ele limpará as compilações antigas, criará o diretório `bin` e compilará todos os ficheiros `.java` do diretório `src`.

```bash
COMPILAR.bat
```

### Passo 2: Iniciar os Nós do Sistema

Execute o script `EXECUTAR_NOS.bat`. Este script iniciará a classe `Simulador`, que criará 5 nós, iniciará o registo RMI e simulará a falha do líder (Nó 5) após 25 segundos.

```bash
EXECUTAR_NOS.bat
```

### Passo 3: Iniciar o Cliente de Monitorização

Execute o script `EXECUTAR_CLIENTE_AUTENTICADO.bat`. O cliente tentará autenticar-se com o líder atual. Após a autenticação, começará a receber e a exibir os relatórios de estado da rede. Quando o líder falhar, o cliente detetará a ausência de relatórios e tentará reconectar-se e autenticar-se com o novo líder.

```bash
EXECUTAR_CLIENTE_AUTENTICADO.bat
```

