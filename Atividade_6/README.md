

# Simulação Completa de Ambiente Distribuído com Núcleo Modular, Comunicação Multigrupo e Implementação Híbrida de Middleware

## 1\. Visão Geral

Este projeto é uma simulação acadêmica de um sistema distribuído complexo, projetado para demonstrar a coexistência e a interoperabilidade de múltiplos paradigmas e algoritmos. O ambiente é dividido em dois grupos de nós distintos, cada um com sua própria tecnologia de middleware e algoritmo de eleição de líder.

A arquitetura é fundamentada em um **núcleo modular**, onde responsabilidades como detecção de falhas, recuperação e captura de estado são delegadas a componentes especializados. O sistema implementa uma **comunicação multigrupo** que permite aos líderes dos diferentes grupos interagirem para eleger um **supercoordenador** global. Essa interação destaca uma **implementação híbrida de middleware**, onde nós baseados em **gRPC** coexistem e colaboram com nós baseados em **Java RMI**.

A simulação (`Simulador.java`) orquestra todo o ambiente, inicializando os nós, permitindo a estabilização e, em seguida, injetando falhas nos líderes de ambos os grupos para validar os mecanismos de tolerância a falhas e recuperação autônoma.

## 2\. Arquitetura e Conceitos

O sistema é construído sobre quatro pilares principais:

### Núcleo Modular

Cada nó no sistema é composto por um conjunto de módulos independentes que gerenciam tarefas específicas, promovendo a separação de responsabilidades e a manutenibilidade.

  * **`GestorHeartbeat`**: Implementa a detecção de falhas através de um mecanismo "PING-PONG" contínuo via Sockets TCP.
  * **`GestorRecuperacao`**: Monitora falhas persistentes e aciona o processo de substituição de um nó que não consegue ser recuperado.
  * **`GeradorNosSubstitutos`**: Simula a criação e inicialização de um novo processo para substituir um nó que falhou permanentemente, garantindo a resiliência do sistema.
  * **`GestorSnapshot`**: Implementa o algoritmo de Chandy-Lamport para capturar um estado global consistente do sistema, orquestrado pelo supercoordenador.

### Implementação Híbrida de Middleware

O ambiente é dividido em dois grupos que utilizam tecnologias de comunicação distintas, demonstrando um cenário heterogêneo.

  * **Grupo A (gRPC & Algoritmo Bully)**: Os nós deste grupo (IDs 1, 2, 3) utilizam gRPC para comunicação interna, definido em `servicos.proto`. A eleição de líder é realizada através do algoritmo Bully.
  * **Grupo B (Java RMI & Algoritmo de Anel)**: Os nós deste grupo (IDs 4, 5, 6) utilizam Java RMI para invocar métodos remotos, definidos na interface `ServicoNoRMI`. A eleição de líder é implementada com um algoritmo de passagem de mensagem em anel.

### Comunicação Multigrupo e Supercoordenação

Mesmo com middlewares diferentes, os grupos podem colaborar.

  * **Descoberta e Comunicação:** Através do módulo `ComunicacaoIntergrupos`, os líderes eleitos de cada grupo utilizam **multicast UDP** para se descobrirem e trocarem mensagens de status e candidaturas.
  * **Eleição de Supercoordenador:** Uma vez que os líderes se conhecem, eles realizam uma eleição para definir um único **Supercoordenador** global, que assume responsabilidades de coordenação em todo o sistema.

### Tolerância a Falhas e Cliente Resiliente

  * **Recuperação de Falhas**: O sistema não apenas detecta falhas de nós e líderes, mas também possui mecanismos para registrar essas falhas e, se necessário, simular a substituição do nó.
  * **Cliente Inteligente**: O `ClienteAutenticado` se conecta e recebe relatórios. Se o líder atual falha, o cliente detecta a ausência de comunicação e inicia um processo para encontrar e se autenticar com o novo líder, de forma transparente.

## 3\. Estrutura do Projeto

```
Atividade_6/
│
├── src/
│   └── main/
│       ├── java/
│       │   └── monitoramento/
│       │       ├── comum/
│       │       ├── grupoa/
│       │       ├── grupob/
│       │       ├── coordenacao/
│       │       ├── autenticacao/
│       │       ├── intergrupo/
│       │       ├── cliente/
│       │       └── Simulador.java
│       └── proto/
│           └── servicos.proto
│
├── target/
│   └── sistema-distribuido-a6-1.0-SNAPSHOT-jar-with-dependencies.jar
│
├── COMPILAR.bat
├── EXECUTAR_TUDO.bat
├── pom.xml
└── README.md
```

## 4\. Como Executar

Para executar a simulação, siga os passos abaixo. Os scripts `.bat` foram criados para automatizar o processo no Windows. Pré-requisitos: Java JDK e Apache Maven instalados e configurados no sistema.

### Passo 1: Compilar o Projeto

Execute o script `COMPILAR.bat`. Ele utilizará o Maven para limpar compilações antigas, criar o diretório `target` e compilar todos os arquivos em um único arquivo `.jar` executável com todas as dependências.

```bash
COMPILAR.bat
```

### Passo 2: Iniciar a Simulação Completa

Execute o script `EXECUTAR_TUDO.bat`. Este script irá:

1.  Chamar o `COMPILAR.bat` para garantir que o projeto está atualizado.
2.  Abrir uma nova janela de terminal para o **Simulador**, que iniciará todos os nós.
3.  Aguardar 10 segundos para a estabilização do sistema.
4.  Abrir uma segunda janela de terminal para o **Cliente**, que irá se autenticar e começar a receber os relatórios.

<!-- end list -->

```bash
EXECUTAR_TUDO.bat
```

### Passo 3: Iniciar o Sistema
Após o EXECUTAR_TUDO.bat abrir as janelas do "Simulador dos Nós" e do "Cliente de Monitorização", a janela de terminal principal ficará em pausa.

1.   Primeiro, feche esta janela de terminal principal que está em pausa.

2.   Em seguida, na janela que sobrou (a do Simulador), escolha a opção 'N' que vai aparecer para continuar. Depois disso, o sistema irá iniciar.

#### Nota Importante: A versão atual do Simulador.java é totalmente automatizada e não aguarda a entrada do teclado para iniciar. A simulação começará a rodar e a exibir os logs de eventos (como a eleição de líderes e falhas) imediatamente após a janela do simulador ser aberta..
