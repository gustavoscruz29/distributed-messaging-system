/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/12/2025
* Ultima alteracao.: 05/12/2025
* Nome.............: Peer
* Funcao...........: Classe peer P2P responsavel por gerenciar
*                    descoberta, comunicacao TCP/UDP, e grupos
*************************************************************** */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Peer {

  private static final int PORTA_DISCOVERY = 6001;
  private static final int PORTA_TCP = 6002;
  private static final int PORTA_UDP = 6003;
  private static final String BROADCAST = "255.255.255.255";

  private final String nomeUsuario;
  private final String meuIp;

  private MensagemListener listener;

  private final Set<String> conhecidos = ConcurrentHashMap.newKeySet();
  private final Map<String, Set<String>> grupos = new ConcurrentHashMap<>();

  private volatile boolean rodando = true;

  private DatagramSocket udpSocket;
  private ServerSocket tcpServer;

  /* ***************************************************************
  * Metodo: Peer
  * Funcao: Construtor do peer. Inicializa variaveis internas,
  *         descobre o IP local e inicia as tres rotinas principais:
  *         servidor TCP, receptor UDP e discovery por broadcast.
  * Parametros: nomeUsuario = nome do usuario local
  * Retorno: void (construtor)
  *************************************************************** */
  public Peer(String nomeUsuario) throws Exception {

    this.nomeUsuario = nomeUsuario;   // guarda o nome escolhido pelo usuario
    this.meuIp = descobrirMeuIp();    // descobre o IP local da maquina

    System.out.println("[Peer] Iniciado: nome=" + nomeUsuario + " IP=" + meuIp);

    iniciarTCP();              // inicia thread que escuta conexoes TCP
    iniciarUDP();              // inicia thread para receber mensagens UDP
    iniciarDiscoveryReceiver(); // inicia rotina que recebe pacotes HELLO

    // envia multiplos HELLO para garantir descoberta mesmo com perda de pacotes
    enviarHelloMultiplo(3, 400);

  }// fim do metodo Peer


  public String getNome() {return nomeUsuario;}
  public void setListener(MensagemListener l) {this.listener = l;}
  public void joinGroup(String g) {joinGrupo(g);}
  public void leaveGroup(String g) {leaveGrupo(g);}
  public void encerrar() {parar();}

  /* ***************************************************************
  * Metodo: joinGrupo
  * Funcao: adiciona o peer ao grupo especificado e avisa todos os
  *         peers conhecidos sobre a entrada (via TCP).
  * Parametros: grupo - nome do grupo ao qual o peer deseja entrar
  * Retorno: void
  *************************************************************** */
  public void joinGrupo(String grupo) {

    if (grupo == null || grupo.isEmpty())
      return; // fim do if

    // cria a estrutura do grupo caso ainda nao exista
    grupos.putIfAbsent(grupo, ConcurrentHashMap.newKeySet());

    // adiciona o proprio IP ao conjunto de membros do grupo
    grupos.get(grupo).add(meuIp);

    // log informativo
    System.out.println("[JOIN] Entrei no grupo: " + grupo);
    System.out.println("[GRUPO " + grupo + "] Membros agora: " + grupos.get(grupo));

    // mensagem no formato: JOIN&grupo&nomeUsuario&ip
    String msg = "JOIN&" + grupo + "&" + nomeUsuario + "&" + meuIp;

    // envia mensagem de join para todos os peers conhecidos
    enviarTcpParaConhecidos(msg);

  }// fim do metodo joinGrupo

  /* ***************************************************************
  * Metodo: leaveGrupo
  * Funcao: remove o peer do grupo especificado e comunica a saida
  *         para todos os peers conhecidos (via TCP).
  * Parametros: grupo - nome do grupo do qual o peer deseja sair
  * Retorno: void
  *************************************************************** */
  public void leaveGrupo(String grupo) {

    if (grupo == null || grupo.isEmpty())
      return; // fim do if

    // verifica se o grupo existe antes de tentar remover
    if (grupos.containsKey(grupo))
      grupos.get(grupo).remove(meuIp); // remove o proprio IP do grupo

    // logs informativos
    System.out.println("[LEAVE] Sai do grupo: " + grupo);
    System.out.println("[GRUPO " + grupo + "] Membros agora: " + grupos.get(grupo));

    // mensagem enviada aos outros peers
    // formato: LEAVE&grupo&usuario&ip
    String msg = "LEAVE&" + grupo + "&" + nomeUsuario + "&" + meuIp;

    // envia aviso de saida a todos os peers conhecidos
    enviarTcpParaConhecidos(msg);

  }// fim do metodo leaveGrupo

  /* ***************************************************************
  * Metodo: enviarMensagem
  * Funcao: envia uma mensagem via UDP para todos os peers que
  *         pertencem ao grupo informado.
  * Parametros: grupo = nome do grupo
  *             texto = conteudo da mensagem
  * Retorno: void
  *************************************************************** */
  public void enviarMensagem(String grupo, String texto) {

    // verifica se o grupo existe localmente
    if (!grupos.containsKey(grupo))
      return; // fim do if

    // formato padrao: grupo&usuario&mensagem
    String msg = grupo + "&" + nomeUsuario + "&" + texto;

    System.out.println("[MSG] Enviando para grupo=" + grupo);

    // percorre copia do conjunto para evitar modificacao concorrente
    for (String ip : new HashSet<>(grupos.get(grupo))) {

      // nao enviar mensagem para si mesmo
      if (ip.equals(meuIp))
        continue; // fim do if

      try (DatagramSocket s = new DatagramSocket()) {

        byte[] dados = msg.getBytes();
        DatagramPacket p = new DatagramPacket(
          dados,
          dados.length,
          InetAddress.getByName(ip),
          PORTA_UDP
        );

        s.send(p); // envia pacote UDP

      } catch (Exception ignored) {
        // excecao ignorada pois perda de mensagem UDP eh esperada
      }
    } // fim do for

    // exibe a propria mensagem imediatamente na interface
    if (listener != null)
      listener.onMensagemRecebida(msg); // fim do if

  }// fim do metodo enviarMensagem

  /* ***************************************************************
  * Metodo: iniciarDiscoveryReceiver
  * Funcao: inicia uma thread que escuta mensagens broadcast HELLO
  *         na rede, permitindo descobrir novos peers.
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void iniciarDiscoveryReceiver() {

    new Thread(() -> {

      try (DatagramSocket ds = new DatagramSocket(PORTA_DISCOVERY)) {

        ds.setBroadcast(true); // habilita recebimento de broadcast
        byte[] buf = new byte[1024]; // buffer para recebimento

        while (rodando) {

          DatagramPacket p = new DatagramPacket(buf, buf.length);
          ds.receive(p); // bloqueia ate receber pacote

          String msg = new String(p.getData(), 0, p.getLength());
          String ip = p.getAddress().getHostAddress();

          if (!rodando)
            break;

          // HELLO indica que um peer deseja ser descoberto
          if (msg.startsWith("HELLO&")) {

            System.out.println("[DISCOVERY] HELLO recebido de " + ip);

            conhecidos.add(ip); // registra novo peer conhecido

            // envia resposta com os grupos locais
            responderHereTcpComGrupos(ip);

            // reenvia informacoes de grupos ao novo peer
            reenviarJoinsParaIp(ip);

          } // fim do if
        } // fim do while

      } catch (Exception e) {

        // imprime erro somente se o peer ainda estiver ativo
        if (rodando)
          e.printStackTrace(); // fim do if

      } // fim do catch

    }, "DISCOVERY-RECV").start(); // nome da thread

  }// fim do metodo iniciarDiscoveryReceiver


  /* ***************************************************************
  * Metodo: enviarHelloMultiplo
  * Funcao: envia repetidas vezes mensagens HELLO para descobrir
  *         outros peers na rede local
  * Parametros: vezes = quantidade de repeticoes do envio
  *             pausaMs = pausa em milissegundos entre envios
  * Retorno: void
  *************************************************************** */
  private void enviarHelloMultiplo(int vezes, int pausaMs) {

    new Thread(() -> {

      // envia "vezes" HELLO, desde que o peer esteja rodando
      for (int i = 0; i < vezes && rodando; i++) { // rodando controla o encerramento
        enviarHELLO();

        try {
          Thread.sleep(pausaMs); // pequena pausa entre os envios
        } catch (InterruptedException ignored) {
          // ignorado pois nao precisa tratar
        }
      } // fim do for

    }, "HELLO-SENDER").start(); // nome da thread
  }// fim do metodo enviarHelloMultiplo

  /* ***************************************************************
  * Metodo: enviarHELLO
  * Funcao: envia um pacote UDP em broadcast contendo HELLO,
  *         informando aos demais peers que este peer existe
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void enviarHELLO() {

    try {
      // mensagem enviada a todos: HELLO&ip
      String msg = "HELLO&" + meuIp;
      byte[] dados = msg.getBytes();

      // cria pacote UDP destinado ao endereco de broadcast
      DatagramPacket p = new DatagramPacket(
        dados,
        dados.length,
        InetAddress.getByName(BROADCAST), // 255.255.255.255
        PORTA_DISCOVERY
      );

      DatagramSocket s = new DatagramSocket();
      s.setBroadcast(true); // habilita broadcast no socket

      s.send(p); // envia pacote
      s.close();

      System.out.println("[HELLO] Broadcast enviado");

    } catch (Exception ignored) {
      // erros ignorados
    }
  }// fim do metodo enviarHELLO

  /* ***************************************************************
  * Metodo: responderHereTcpComGrupos
  * Funcao: envia uma resposta HERE via TCP para o peer que enviou
  *         HELLO, informando quais grupos este peer participa
  * Parametros: destinoIp = ip do peer que devera receber o HERE
  * Retorno: void
  *************************************************************** */
  private void responderHereTcpComGrupos(String destinoIp) {

    new Thread(() -> {

      try (
        // abre conexao TCP diretamente com o peer descoberto
        Socket socket = new Socket(destinoIp, PORTA_TCP);
        PrintWriter pw = new PrintWriter(socket.getOutputStream(), true)
      ) {

        // transforma lista de grupos em string: g1,g2,g3
        String gruposStr = String.join(",", grupos.keySet());

        // formato HERE: HERE&meuIp&grupo1,grupo2,grupo3
        pw.println("HERE&" + meuIp + "&" + gruposStr);

        System.out.println("[HERE] Enviado para " + destinoIp);

      } catch (Exception ignored) {
        // falha ignorada porque peer pode ter caido entre HELLO e HERE
      }

    }, "HERE-RESP").start(); // fim da thread

  }// fim do metodo responderHereTcpComGrupos


  /* ***************************************************************
  * Metodo: reenviarJoinsParaIp
  * Funcao: reenviar todos os JOIN deste peer para um peer recem descoberto,
  *         garantindo que ele receba os grupos dos quais participamos
  * Parametros: destinoIp = ip do peer que devera receber os JOIN
  * Retorno: void
  *************************************************************** */
  private void reenviarJoinsParaIp(String destinoIp) {

    // percorre cada grupo existente neste peer
    for (String grupo : grupos.keySet()) {

      // envia novamente o JOIN para o novo peer descoberto
      enviarTcpParaIp(destinoIp,
        "JOIN&" + grupo + "&" + nomeUsuario + "&" + meuIp
      );
    } // fim do for

  }// fim do metodo reenviarJoinsParaIp


  /* ***************************************************************
  * Metodo: iniciarTCP
  * Funcao: inicializa o servidor TCP responsavel por receber
  *         mensagens HERE, JOIN e LEAVE de outros peers
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void iniciarTCP() {

    new Thread(() -> {

      try {
        // cria o servidor TCP na porta definida
        tcpServer = new ServerSocket(PORTA_TCP);
        System.out.println("[TCP] Servidor escutando porta " + PORTA_TCP);

        // loop principal de atendimento
        while (rodando) {

          // aguarda conexao de outro peer
          Socket cli = tcpServer.accept();

          // cada conexao eh tratada em uma thread separada
          new Thread(() -> tratarTCP(cli)).start();

        } // fim do while

      } catch (Exception e) {

        // se nao estiver encerrando, mostrar erro
        if (rodando)
          e.printStackTrace(); // fim do if

      }
    }, "TCP-SERVER").start(); // nome da thread

  }// fim do metodo iniciarTCP


  /* ***************************************************************
  * Metodo: tratarTCP
  * Funcao: processa mensagens recebidas via TCP (HERE, JOIN e LEAVE)
  * Parametros: cli = socket da conexao recebida de outro peer
  * Retorno: void
  *************************************************************** */
  private void tratarTCP(Socket cli) {

    try (BufferedReader br = new BufferedReader(
          new InputStreamReader(cli.getInputStream()))) { // fluxo de leitura da mensagem TCP

      String msg = br.readLine(); // le a linha enviada pelo peer remoto

      // se nao houver mensagem ou o peer estiver encerrando, aborta
      if (msg == null || !rodando)
        return; // fim do if

      // ip do peer que enviou a mensagem
      String remoteIp = cli.getInetAddress().getHostAddress();
      System.out.println("[TCP] Msg recebida de " + remoteIp + ": " + msg);

      /* -----------------------------------------------------------
      * PROCESSAMENTO DA MENSAGEM HERE
      * ----------------------------------------------------------- */
      if (msg.startsWith("HERE&")) {

        String[] p = msg.split("&", 3); // p[1] = ip, p[2] = lista de grupos
        if (p.length >= 2) {

          // adiciona o ip remoto na lista de conhecidos
          conhecidos.add(p[1]);

          // se o remoto informou grupos existentes, adiciona tambem
          if (p.length == 3 && !p[2].isEmpty()) {
            for (String g : p[2].split(",")) {

              // cria grupo se nao existir
              grupos.putIfAbsent(g, ConcurrentHashMap.newKeySet());

              // adiciona o ip remoto ao grupo informado
              grupos.get(g).add(p[1]);

            } // fim do for
          } // fim do if
        } // fim do if

      } // fim do if (HERE)

      /* -----------------------------------------------------------
      * PROCESSAMENTO DA MENSAGEM JOIN
      * ----------------------------------------------------------- */
      else if (msg.startsWith("JOIN&")) {

        String[] p = msg.split("&", 4); // JOIN & grupo & usuario & ip
        if (p.length == 4) {

          String grupo = p[1];
          String ipRem = p[3];

          System.out.println("[JOIN] " + ipRem + " entrou no grupo " + grupo);

          // cria o grupo se nao existir
          grupos.putIfAbsent(grupo, ConcurrentHashMap.newKeySet());

          // adiciona peer ao grupo
          grupos.get(grupo).add(ipRem);

          // adiciona o ip remoto aos conhecidos
          conhecidos.add(ipRem);

          System.out.println("[GRUPO " + grupo + "] Membros agora: " + grupos.get(grupo));

        } // fim do if
      } // fim do else if (JOIN)

      /* -----------------------------------------------------------
      * PROCESSAMENTO DA MENSAGEM LEAVE
      * ----------------------------------------------------------- */
      else if (msg.startsWith("LEAVE&")) {

        String[] p = msg.split("&", 4); // LEAVE & grupo & usuario & ip
        if (p.length == 4) {

          String grupo = p[1];
          String ipRem = p[3];

          System.out.println("[LEAVE] " + ipRem + " saiu do grupo " + grupo);

          // remove do grupo se existir
          if (grupos.containsKey(grupo))
            grupos.get(grupo).remove(ipRem); // fim do if

          System.out.println("[GRUPO " + grupo + "] Membros agora: " + grupos.get(grupo));

        } // fim do if
      } // fim do else if (LEAVE)

    } catch (Exception ignored) {

      // ignoramos falhas para manter estabilidade

    } finally {
      // fecha a conexao TCP
      try {
        cli.close();
      } catch (Exception ignored) {
      }

    } // fim do finally

  }// fim do metodo tratarTCP


  /* ***************************************************************
  * Metodo: enviarTcpParaConhecidos
  * Funcao: envia uma mensagem TCP para todos os IPs conhecidos
  * Parametros: msg = texto enviado para cada peer conhecido
  * Retorno: void
  *************************************************************** */
  private void enviarTcpParaConhecidos(String msg) {

    // percorre um clone do conjunto para evitar problemas de concorrencia
    for (String ip : new HashSet<>(conhecidos)) {

      // nao envia para si mesmo
      if (!ip.equals(meuIp))
        enviarTcpParaIp(ip, msg); // fim do if

    } // fim do for
  }// fim do metodo enviarTcpParaConhecidos

  /* ***************************************************************
  * Metodo: enviarTcpParaIp
  * Funcao: envia uma mensagem TCP para um unico IP especificado
  * Parametros: ip = destino do pacote TCP
  *             msg = texto enviado
  * Retorno: nenhum
  *************************************************************** */
  private void enviarTcpParaIp(String ip, String msg) {

    try (Socket s = new Socket()) {

      // conecta ao destino com timeout de 800ms para evitar travar
      s.connect(new InetSocketAddress(ip, PORTA_TCP), 800);

      // envia a linha via TCP
      new PrintWriter(s.getOutputStream(), true).println(msg);

    } catch (Exception ignored) {
      // erros de conexao sao ignorados porque peers podem estar offline
    }//fim do catch

  }// fim do metodo enviarTcpParaIp

  /* ***************************************************************
  * Metodo: iniciarUDP
  * Funcao: inicializa a recepcao de mensagens UDP em uma thread separada
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void iniciarUDP() {

    // cria uma nova thread para escutar a porta UDP
    new Thread(() -> {
      try {
        // cria o socket UDP para escutar na porta 6003
        udpSocket = new DatagramSocket(PORTA_UDP);
        System.out.println("[UDP] Escutando porta " + PORTA_UDP);

        byte[] buf = new byte[4096]; // buffer de recepcao de dados

        while (rodando) {

          // aguarda um pacote UDP
          DatagramPacket p = new DatagramPacket(buf, buf.length);
          udpSocket.receive(p); // recebe o pacote

          if (!rodando) // caso o peer tenha sido parado, sai do loop
            break; // fim do if

          // converte os dados do pacote em string
          String msg = new String(p.getData(), 0, p.getLength());

          // obtem o IP de origem da mensagem
          String ipRem = p.getAddress().getHostAddress();

          // ignora mensagens enviadas por ele mesmo
          if (!ipRem.equals(meuIp)) {
            // adiciona o IP do remetente aos conhecidos
            conhecidos.add(ipRem);

            // imprime a mensagem recebida para debug
            System.out.println("[UDP] Msg recebida de " + ipRem + ": " + msg);

            // chama o listener para tratar a mensagem
            if (listener != null)
              listener.onMensagemRecebida(msg); // fim do if
          } // fim do if

        } // fim do while
      } catch (Exception e) {
        if (rodando) // caso o processo esteja rodando, imprime o erro
          e.printStackTrace();
      } //fim do catch
    }, "UDP-CHAT").start(); // fim do thread

  } // fim do metodo iniciarUDP

  /* ***************************************************************
  * Metodo: parar
  * Funcao: realiza o encerramento do peer, fechando conexoes e limpando recursos
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  public void parar() {
    System.out.println("[Peer] Encerrando...");

    rodando = false; // sinaliza que o peer nao esta mais rodando

    // envia a mensagem LEAVE para todos os grupos que o peer pertence
    for (String grupo : grupos.keySet()) {
      String msg = "LEAVE&" + grupo + "&" + nomeUsuario + "&" + meuIp;
      enviarTcpParaConhecidos(msg); // envia a mensagem LEAVE via TCP
    }//fim do for

    grupos.clear(); // limpa todos os grupos
    conhecidos.clear(); // limpa a lista de conhecidos

    // tenta fechar o socket UDP de recepcao
    try {
      if (udpSocket != null && !udpSocket.isClosed())
        udpSocket.close(); // fecha o socket UDP
    }//fim do try
    catch (Exception ignored) { // caso falhe, ignora
    }//fim do catch

    // tenta fechar o servidor TCP
    try {
      if (tcpServer != null && !tcpServer.isClosed())
        tcpServer.close(); // fecha o servidor TCP
    }//fim do try
    catch (Exception ignored) { // caso falhe, ignora
    }//fim do catch

    // mensagem de log informando que o peer foi encerrado
    System.out.println("[Peer] Encerrado."); 
  } // fim do metodo parar


  /* ***************************************************************
  * Metodo: descobrirMeuIp
  * Funcao: descobre o endereco IP local do peer
  * Parametros: nenhum
  * Retorno: endereco IP do peer como String
  *************************************************************** */
  private String descobrirMeuIp() {
    try (DatagramSocket s = new DatagramSocket()) { // cria um socket para descobrir o IP
      // conecta com o servidor DNS do Google para determinar o IP local
      s.connect(InetAddress.getByName("8.8.8.8"), 10000); 
      return s.getLocalAddress().getHostAddress(); // retorna o endereco IP local
    }//fim do try
    catch (Exception e) { // se ocorrer um erro
      return "127.0.0.1"; // retorna o IP de loopback como fallback
    }//fim do catch
  } // fim do metodo descobrirMeuIp


  /* ***************************************************************
  * Metodo: getConhecidos
  * Funcao: retorna o conjunto de IPs conhecidos
  * Parametros: nenhum
  * Retorno: conjunto imutavel com os IPs dos conhecidos
  *************************************************************** */
  public Set<String> getConhecidos() {
    return Collections.unmodifiableSet(conhecidos); // retorna o conjunto de conhecidos de forma imutavel
  } // fim do metodo getConhecidos


  /* ***************************************************************
  * Metodo: getGrupos
  * Funcao: retorna o map de grupos com seus respectivos membros
  * Parametros: nenhum
  * Retorno: map imutavel com os grupos e seus membros
  *************************************************************** */
  public Map<String, Set<String>> getGrupos() {
    return Collections.unmodifiableMap(grupos); // retorna o mapa de grupos de forma imutavel
  }// fim do metodo getGrupos

}//fim da classe Peer
