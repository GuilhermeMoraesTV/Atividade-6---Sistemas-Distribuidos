Sistema Distribuído de Monitoramento com Tolerância a Falhas, Dois Grupos e Supercoordenador
1. Visão Geral
Este projeto acadêmico, desenvolvido para a disciplina de Sistemas Distribuídos, simula um avançado sistema de monitoramento de recursos (CPU, memória, etc.) em uma rede de servidores distribuídos em dois grupos distintos:

Grupo A: Utiliza o algoritmo Bully para eleição de líder e se comunica via gRPC.

Grupo B: Utiliza um algoritmo de Anel para eleição de líder e se comunica via Java RMI.

O sistema demonstra conceitos complexos como a coexistência de diferentes arquiteturas de comunicação, eleição de um supercoordenador global, detecção de falhas, snapshots distribuídos (Chandy-Lamport) e recuperação de nós. A simulação (Simulador.java) orquestra a inicialização dos dois grupos, estabiliza o sistema e, em seguida, simula a falha dos líderes de cada grupo para demonstrar a robustez e a capacidade de recuperação autônoma do sistema.

2. Funcionalidades Principais
Monitoramento de Recursos: O líder de cada grupo coleta periodicamente o estado de uso de CPU, memória e carga do sistema de todos os nós ativos em seu respectivo grupo.

Deteção de Falhas por Heartbeat: Cada nó monitora os outros através de um mecanismo de "PING-PONG" via Sockets TCP, permitindo a detecção rápida de falhas.

Eleição de Líder Multi-Algoritmo:

Grupo A (Bully): Se a falha do coordenador é detectada, o algoritmo Bully elege o nó de maior ID entre os ativos como novo líder.

Grupo B (Anel): A eleição é realizada passando uma mensagem em anel para determinar o novo líder.

Supercoordenador Global: Após a eleição dos líderes de grupo, um supercoordenador é eleito entre eles para gerenciar tarefas globais, como a captura de snapshots distribuídos.

Comunicação Intergrupos: Os líderes de grupo se comunicam via multicast para descobrir outros grupos, trocar informações de status e eleger o supercoordenador.

Snapshot Distribuído (Chandy-Lamport): O supercoordenador pode iniciar uma captura de estado global consistente (snapshot) de todo o sistema, envolvendo ambos os grupos.

Comunicação em Grupo (Multicast): Relatórios de estado consolidados são enviados pelos líderes para um grupo multicast UDP, permitindo que múltiplos clientes monitorem o sistema em tempo real.

Acesso por Autenticação: Um cliente (ClienteAutenticado.java) deve primeiro se autenticar com o líder do seu grupo para começar a receber os relatórios de monitoramento.

Cliente Resiliente: A aplicação cliente detecta a ausência de relatórios (indicando uma possível falha de líder) e tenta se re-autenticar automaticamente com o novo líder eleito.

3. Tecnologias Utilizadas
Linguagem: Java

Build: Apache Maven

Comunicação:

gRPC: Para comunicação síncrona e eficiente entre os nós do Grupo A.

Java RMI (Remote Method Invocation): Para a chamada de métodos remotos entre os nós do Grupo B.

Sockets TCP/IP: Para a comunicação do mecanismo de Heartbeat.

Sockets UDP Multicast: Para a descoberta de grupos e disseminação de relatórios de monitoramento.

4. Estrutura do Projeto
Atividade_6/
│
├── src/
│   └── main/
│       ├── java/
│       │   └── monitoramento/
│       │       ├── comum/         # Classes de utilidade compartilhadas
│       │       ├── grupoa/        # Lógica específica do Grupo A (Bully, gRPC)
│       │       ├── grupob/        # Lógica específica do Grupo B (Anel, RMI)
│       │       ├── coordenacao/   # Classes para coordenação e multicast
│       │       ├── autenticacao/  # Servidor de autenticação
│       │       ├── intergrupo/    # Comunicação entre grupos
│       │       ├── cliente/       # Clientes de monitoramento
│       │       ├── Simulador.java # Ponto de entrada da simulação
│       │       └── ...
│       └── proto/
│           └── servicos.proto     # Definição do serviço gRPC
│
├── target/                        # Diretório gerado pelo Maven após a compilação
│   └── sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar
│
├── COMPILAR.bat                   # Script para compilar o projeto com Maven
├── EXECUTAR_TUDO.bat              # Script para compilar e executar a simulação completa
├── pom.xml                        # Arquivo de configuração do Maven
└── README.md                      # Este arquivo
5. Como Executar (Passo a Passo)
Siga estas instruções para compilar e rodar a simulação completa.

Passo 1: Compilar o Projeto e Gerar o Arquivo .jar
Antes de tudo, é necessário compilar o código-fonte e empacotar a aplicação em um arquivo .jar que contenha todas as dependências. O script COMPILAR.bat foi criado para automatizar este processo usando o Maven.

Abra um terminal ou prompt de comando na pasta raiz do projeto (Atividade_6).

Execute o script COMPILAR.bat:

Bash

COMPILAR.bat
Este comando irá:

Limpar compilações antigas.

Baixar as dependências definidas no pom.xml.

Compilar o código-fonte Java.

Gerar o código gRPC a partir do arquivo .proto.

Empacotar tudo em um único arquivo executável chamado sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar dentro de uma nova pasta chamada target.

Aguarde até que a mensagem "Compilacao e empacotamento finalizados com SUCESSO!" seja exibida.

Passo 2: Executar a Simulação Completa
O script EXECUTAR_TUDO.bat automatiza a inicialização de todos os componentes do sistema em janelas separadas.

No mesmo terminal, execute o script EXECUTAR_TUDO.bat:

Bash

EXECUTAR_TUDO.bat
O que acontecerá:

Janela 1 (Compilador): O script primeiro chama o COMPILAR.bat novamente para garantir que a versão mais recente do código está sendo usada. Esta janela fechará automaticamente.

Janela 2 (Simulador dos Nós): Uma nova janela intitulada "Simulador dos Nós" será aberta. Ela iniciará o Simulador.java, que por sua vez inicializará todos os 6 nós (3 do Grupo A e 3 do Grupo B), o registro RMI e começará a simulação. Você verá os logs de inicialização de cada nó.

Janela 3 (Cliente de Monitorização): Após uma pausa de 10 segundos para estabilização do sistema, uma terceira janela intitulada "Cliente de Monitorização" será aberta. Este é o ClienteAutenticado que tentará se autenticar e começará a exibir os relatórios de monitoramento enviados pelos líderes.

Passo 3: Observar a Simulação e a Tolerância a Falhas
Agora você pode observar o sistema em ação nas janelas abertas:

Na janela do Simulador: Acompanhe os logs. O simulador anunciará quando está prestes a simular a falha do líder do Grupo A (Nó 3) e, posteriormente, do líder do Grupo B (Nó 6).

Logs dos Nós: Você verá mensagens de detecção de falha, o início dos processos de eleição (Bully e Anel) e, finalmente, a eleição dos novos líderes (Nó 2 para o Grupo A e Nó 5 para o Grupo B).

Na janela do Cliente: O cliente, que estava recebendo relatórios do líder original, detectará um timeout (ausência de relatórios). Ele então entrará em um estado de "tentando encontrar um novo líder", se re-autenticará com o novo líder eleito e voltará a receber os relatórios, demonstrando a resiliência do sistema.

Passo 4: Encerrar a Simulação
Quando a simulação chegar ao fim, a janela do "Simulador dos Nós" exibirá a mensagem "FINALIZANDO SIMULAÇÃO" e depois "SIMULAÇÃO CONCLUÍDA COM SUCESSO".

Você pode fechar manualmente todas as janelas do prompt de comando que foram abertas.
