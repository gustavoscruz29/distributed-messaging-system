/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 10/11/2025
* Ultima alteracao.: 17/11/2025
* Nome.............: Servidor
* Funcao...........: Classe servidora
*************************************************************** */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {

  // portas existentes (mantidas)
  private static final int PORTA_CLIENTE = 6789; // JOIN/LEAVE (TCP) e SEND (UDP)
  private static final int PORTA_DISCOVERY = 6790; // discovery por clientes (clients broadcast DISCOVERY_REQUEST)
  private static final int PORTA_SERVIDORES = 6791; // broadcast de presenca entre servidores (HELLOs + interval
                                                    // discovery)
  private static final int PORTA_REPLICACAO = 6792; // replicacao do estado entre servidores do mesmo intervalo

  private static final int INTERVALO_HELLO = 1000; // ms - envio de HELLO
  private static final int TIMEOUT_SERVIDOR = 3000; // ms - sem HELLO = morto
  private static final int INTERVALO_TIMEOUT = 1000; // ms - checa inativos

  private static final int TIMEOUT_REQUEST_STATE = 3000; // ms - timeout de connect/request state
  private static final int MAX_TENTATIVAS_ESTADO = 3; // numero de tentativas de pedir estado

  private static final String[] INTERVALOS = { "A-I", "J-R", "S-Z" };

  // informacoes desta instancia
  private String meuIp;
  private volatile int ciclosSemHelloLider = 0; // quantos ciclos consecutivos sem HELLO
  private static final int CICLOS_TOLERADOS_SEM_LIDER = 3; // por exemplo, 3 * TIMEOUT_SERVIDOR
  private static final boolean DEBUG_PRESENCA = false; // true para logs extras de debug
  private volatile boolean souBackup = false;
  private volatile long ultimoHelloLider = 0; // controla a inatividade do lider
  private volatile String intervaloResponsavel = null; // ex: "A-I"
  private volatile boolean souLider = false; // se este servidor e lider do seu intervaloResponsavel
  private volatile boolean estadoSincronizado = false;
  private volatile long instanteInicial = System.currentTimeMillis();

  
  private Map<String, List<Usuario>> grupos = new ConcurrentHashMap<>();
  private Map<String, Map<String, Long>> servidoresAtivosPorIntervalo = new ConcurrentHashMap<>();
  private Map<String, String> liderAtualPorIntervalo = new ConcurrentHashMap<>();
  private final Set<String> servidoresMesmoIntervalo = ConcurrentHashMap.newKeySet();

  /* ***************************************************************
   * Metodo: iniciar
   * Funcao: inicializa o servidor, descobre seu IP, define o intervalo
   *         que ira gerenciar, inicia as threads de presenca,
   *         replicacao e descoberta, e abre os sockets TCP e UDP
   *         para atendimento aos clientes.
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  public void iniciar() {
    try {
      meuIp = descobrirMeuIp(); // descobre o IP real da maquina
      System.out.println("Servidor iniciado em " + meuIp);

      // inicializa as estruturas de controle para cada intervalo
      // cada intervalo possui seu mapa de servidores ativos e um lider atual
      for (String intervalo : INTERVALOS) {
        servidoresAtivosPorIntervalo.put(intervalo, new ConcurrentHashMap<>());
        liderAtualPorIntervalo.put(intervalo, "NONE");
      }//fim do for

      descobrirEAssumirIntervalo(); // tenta determinar qual intervalo assumir (ou ser backup)
      iniciarEscutaPresenca(); // inicia escuta dos HELLO e HELLO_LIDER e atualiza a tabela de presenca
      iniciarBroadcastPresenca(); // inicia o broadcast periodico de presenca
      iniciarTimeoutMonitor(); // thread que monitora ausencia de HELLO_LIDER do lider atual
      iniciarReplicacao(); // thread que recebe atualizacoes de estado de outros servidores
      iniciarDiscovery(); // escuta pedidos de descoberta vindos de novos servidores
      // thread periodica que envia o estado para backups e servidores presentes
      iniciarReplicacaoPeriodica();
      

      // sockets para clientes
      ServerSocket serverSocket = new ServerSocket(PORTA_CLIENTE);
      DatagramSocket udpSocket = new DatagramSocket(PORTA_CLIENTE);

      new EscutadorUDP(udpSocket).start(); 

      // Loop principal: aceita novas conexoes TCP de clientes
      // Cada cliente eh tratado em uma thread propria
      while (true) {
        Socket cliente = serverSocket.accept(); 
        new TratadorTCP(cliente).start();
      }//fim do while

    }//fim do try
    catch (Exception e) {
      e.printStackTrace();
    }//fim do catch
  }//fim do metodo iniciar

  /* ***************************************************************
   * Metodo: descobrirMeuIp
   * Funcao: Determina o IP real da maquina.
   * Parametros: nenhum
   * Retorno: String contendo o IP descoberto ou 127.0.0.1 em caso de erro
   *************************************************************** */
  private String descobrirMeuIp() {
    try {
      DatagramSocket socket = new DatagramSocket();
      socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
      String ip = socket.getLocalAddress().getHostAddress();
      socket.close();
      return ip;
    }//fum do try
    // Em caso de falha, retorna loopback para garantir que o servidor inicie
    catch (Exception e) {
      return "127.0.0.1";
    }//fim do catch
  }//fim do metodo descobrirMeuIp


  /* ***************************************************************
   * Metodo: descobrirEAssumirIntervalo
   * Funcao: Realiza processo de descoberta inicial na rede para
   *         decidir qual intervalo o servidor deve assumir.
   *         Resolve disputa entre servidores iniciantes usando
   *         tie-break por menor IP. Pode assumir intervalo ou
   *         entrar como backup.
   * Parametros: nenhum
   * Retorno: nenhum
   *************************************************************** */
  private void descobrirEAssumirIntervalo() {
    DatagramSocket tempListener = null;
    Thread listenerThread = null;
    try {
      // pequeno atraso para diminuir colisao entre servidores iniciando juntos
      Random rnd = new Random();
      Thread.sleep(200 + rnd.nextInt(400));

      // armazena IPs de outros servidores que tambem estao iniciando agora
      final Set<String> peerStarters = Collections.synchronizedSet(new HashSet<>());

      try {
        // cria um socket temporario para ESCUTAR pedidos de discovery
        tempListener = new DatagramSocket(null);
        tempListener.setReuseAddress(true);
        tempListener.bind(new InetSocketAddress(PORTA_SERVIDORES));
        final DatagramSocket sockRef = tempListener;

        // thread que escuta RESPOSTAS e PEDIDOS de outros servidores
        listenerThread = new Thread(() -> {
          byte[] buf = new byte[1024];
          DatagramPacket p = new DatagramPacket(buf, buf.length);
          long stopAt = System.currentTimeMillis() + 7000; // ativo por ~7s
          while (System.currentTimeMillis() < stopAt) {
            try {
              sockRef.setSoTimeout(1000);
              sockRef.receive(p);
              String received = new String(p.getData(), 0, p.getLength()).trim();
               
              // se outro servidor enviou REQUEST, significa que tambem esta iniciando
              if (received.startsWith("INTERVAL_DISCOVERY_REQUEST&")) {
                String origemIp = p.getAddress().getHostAddress();
                peerStarters.add(origemIp); // registra que outro servidor iniciou ao mesmo tempo

                // responde para ele indicando que ainda nao assumimos nenhum intervalo
                String reply = "INTERVAL_DISCOVERY_RESPONSE&UNASSIGNED&" + meuIp;
                byte[] data = reply.getBytes();
                DatagramPacket replyPacket = new DatagramPacket(data, data.length, p.getAddress(), p.getPort());
                sockRef.send(replyPacket);
              }//fim do if
            }//fim do try 
            catch (SocketTimeoutException ste) {
              // sem pacotes no timeout, apenas continua o loop
            }//fim do catch 
            catch (IOException ioe) {
              // ignora erros eventuais de IO
            }//fim do catch
          }//fim do while
        }, "DiscoveryTempListener-" + meuIp);
        listenerThread.setDaemon(true);
        listenerThread.start();
      }//fim do try 
      catch (SocketException se) {
        // se nao foi possivel criar o listener temporario, segue sem ele
        if (tempListener != null) {
          try {
            tempListener.close();
          }//fim do try 
          catch (Exception ignore) {
          }//fim do catch
          tempListener = null;
        }//fim do if
      }//fim do catch

      // dois ciclos de discovery (melhorar a questao das colisoes)
      // lista de intervalos ocupados por servidores ja ativos
      Set<String> intervalosOcupados = new HashSet<>();
      long now = System.currentTimeMillis();
      long overallDeadline = now + 3000 * 2; // 2 ciclos de 3s (total ~6s)
      // usocket temporario para envio (nao ligado ao listener)
      DatagramSocket sendSocket = null; // socket para ENVIAR mensagens de discovery
      try {
        sendSocket = new DatagramSocket();
        sendSocket.setBroadcast(true);

        while (System.currentTimeMillis() < overallDeadline) {
          // envia REQUEST informando que estamos iniciando
          String req = "INTERVAL_DISCOVERY_REQUEST&" + meuIp;
          byte[] dados = req.getBytes();

          // broadcast global (255.255.255.255)
          try {
            DatagramPacket pacoteGlobal = new DatagramPacket(dados, dados.length,
                InetAddress.getByName("255.255.255.255"), PORTA_SERVIDORES);
            sendSocket.send(pacoteGlobal);
          }//fim do try
          catch (Exception e) {
            // falha de broadcast global eh ignoravel
          }//fim do catch

          // envia para os broadcasts das interfaces
          try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
              NetworkInterface ni = interfaces.nextElement();
              try {
                if (ni.isLoopback() || !ni.isUp())
                  continue;
              }//fim do try 
              catch (Exception ex) {
                continue;
              }//fim do catch
              for (InterfaceAddress ifAddr : ni.getInterfaceAddresses()) {
                InetAddress b = ifAddr.getBroadcast();
                if (b == null)
                  continue;
                try {
                  DatagramPacket pacoteLocal = new DatagramPacket(dados, dados.length, b, PORTA_SERVIDORES);
                  sendSocket.send(pacoteLocal);
                }//fim do try
                catch (Exception ignore) {
                }//fim do catch
              }//fim do for
            }//fim do while
          } 
          catch (Exception ignore) {
          }//fim do catch

          // aguarda por respostas de servidores ativos
          long cycleWaitUntil = Math.min(System.currentTimeMillis() + 1000, overallDeadline);
          sendSocket.setSoTimeout(300);
          while (System.currentTimeMillis() < cycleWaitUntil) {
            try {
              byte[] buffer = new byte[1024];
              DatagramPacket resposta = new DatagramPacket(buffer, buffer.length);
              sendSocket.receive(resposta);
              String msg = new String(resposta.getData(), 0, resposta.getLength()).trim();

              // processa resposta de um servidor
              if (msg.startsWith("INTERVAL_DISCOVERY_RESPONSE&")) {
                String[] p = msg.split("&");
                if (p.length >= 3) {
                  String intervalo = p[1].trim();
                  String ip = p[2].trim();

                  // se responderam "UNASSIGNED", significa que esse peer tambehm esta iniciando
                  // (starter)
                  if ("UNASSIGNED".equals(intervalo)) {
                    // este servidor tambem esta iniciando agora
                    peerStarters.add(ip);
                  }//fim do if
                  else {
                    // este servidor ja esta ativo e declarou seu intervalo
                    intervalosOcupados.add(intervalo);
                    // atualiza mapa de ativos com o ip respondente
                    Map<String, Long> mapa = servidoresAtivosPorIntervalo.get(intervalo);
                    if (mapa != null)
                      mapa.put(ip, System.currentTimeMillis());
                  }//fim do else
                }//fim do if
              }//fim do if
            }//fim do try 
            catch (SocketTimeoutException ste) {
              // sem resposta nesse pequeno intervalo = repete ateh cycleWaitUntil
            }// fim do catch
            catch (IOException ioe) {
              // ignora erros de IO nessa fase
              break;
            }//fim do catch
          }//fim do while

          // pequeno intervalo antes de enviar novo REQUEST
          Thread.sleep(150 + rnd.nextInt(200));
        }//fim do while
      } 
      finally {
        // fecha socket de envio
        if (sendSocket != null && !sendSocket.isClosed()) {
          try {
            sendSocket.close();
          }//fim do try
          catch (Exception ignore) {
          }//fim do catch
        }//fim do if
      }//fim do finally

      String escolhido = null;
      for (String interval : INTERVALOS) {
        if (!intervalosOcupados.contains(interval)) {
          escolhido = interval;
          break;
        }//fim do if
      }//fim do for

      // tentativa de escolher intervalo livre
      if (escolhido == null) {
        // Todos ocupados = escolhe backup de forma balanceada
        int countAI = servidoresAtivosPorIntervalo.get("A-I").size();
        int countJR = servidoresAtivosPorIntervalo.get("J-R").size();
        int countSZ = servidoresAtivosPorIntervalo.get("S-Z").size();

        if (countAI <= countJR && countAI <= countSZ) {
          escolhido = "A-I";
        }//fim do if
        else if (countJR <= countSZ) {
          escolhido = "J-R";
        }//fim do else if
        else {
          escolhido = "S-Z";
        }//fim do else

        this.souBackup = true;
        System.out.println("Todos os intervalos ocupados. Entrando como backup de " + escolhido);
      }//fim do if
      else {
        // se varios servidores estao iniciando ao mesmo tempo, decide pelo menor IP
        boolean outrosStartersPresentes = false;
        if (!peerStarters.isEmpty()) {
          // remove self se presente
          Set<String> others = new HashSet<>(peerStarters);
          others.remove(meuIp);
          if (!others.isEmpty()) {
            outrosStartersPresentes = true;
            // compare IP strings lexicograficamente
            String menor = meuIp;
            for (String s : others) {
              if (s.compareTo(menor) < 0)
                menor = s;
            }//fim do for
            if (!menor.equals(meuIp)) {
              // Ha outro starter com IP menor = eu devo entrar como backup desse intervalo
              this.souBackup = true;
              System.out.println(
                  "Outro starter com menor IP detectado (" + menor + "). Entrando como backup de " + escolhido);
            }//fim do if
            else {
              // meuIp eh o menor = eu assumo como principal
              this.souBackup = false;
              System.out.println("Sou o starter com menor IP entre iniciantes. Assumindo: " + escolhido);
            }//fim do else
          }//fim do if
          else {
            // nao ha outros starters, posso assumir normalmente
            this.souBackup = false;
            System.out.println("Intervalo livre encontrado. Assumindo: " + escolhido);
          }//fim do else
        } else {
          // nenhuma outra inicializacao detectada: assumir normalmente
          this.souBackup = false;
          System.out.println("Intervalo livre encontrado. Assumindo: " + escolhido);
        }//fim do else
      }//fim do else

      this.intervaloResponsavel = escolhido;

      // Tenta recuperar estado de algum servidor ativo do mesmo intervalo
      Map<String, Long> ativos = servidoresAtivosPorIntervalo.get(escolhido);
      if (ativos != null && !ativos.isEmpty()) {
        for (String ip : ativos.keySet()) {
          if (!ip.equals(meuIp)) {
            solicitarEstadoDeOutroServidor(ip);
            if (!grupos.isEmpty())
              break;
          }//fim do if
        }//fim do for
      }//fim do if

      // forca calculo inicial de lideranca ou preparacao de backup
      try {
        recalcularLider(this.intervaloResponsavel);
      }//fim do try
      catch (Exception e) {
        System.out.println("Aviso: recalcularLider falhou na inicializacao: " + e.getMessage());
      }//fim do catch

    } catch (Exception e) {
      System.out.println("Falha ao descobrir intervalos: " + e.getMessage());
      if (this.intervaloResponsavel == null) {
        this.intervaloResponsavel = INTERVALOS[0];
        System.out.println("Assumindo por default: " + this.intervaloResponsavel);
        try {
          recalcularLider(this.intervaloResponsavel);
        }//fim do try
        catch (Exception ignore) {
        }//fim do catch
      }//fim do if
    } finally {
      // garante parada do listener temporario
      try {
        if (tempListener != null && !tempListener.isClosed())
          tempListener.close();
      }//fim do try
      catch (Exception ignore) {
      }//fim do catch
      try {
        if (listenerThread != null && listenerThread.isAlive())
          listenerThread.interrupt();
      }//fim do try
      catch (Exception ignore) {
      }//fim do catch
    }//fim do finally
  }//fim do metodo descobrirEAssumirIntervalo

  /* ***************************************************************
   * Metodo: iniciarBroadcastPresenca
   * Funcao: Envia periodicamente mensagens HELLO para que outros
   *         servidores detectem este servidor, seu papel (lider
   *         ou backup) e seu intervalo.
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarBroadcastPresenca() {
    // thread para envio continuo de mensagens de presenca
    new Thread(() -> { 
      DatagramSocket socket = null;
      try {
        socket = new DatagramSocket(); // socket para envio
        socket.setBroadcast(true); // habilita envio para broadcast
        Random rnd = new Random();

        while (true) {
          // define a tag principal da mensagem: HELLO ou HELLO_LIDER
          String papel = souLider ? "HELLO_LIDER" : "HELLO";
          // timestamp usado pelos receptores para detectar expiracao
          long ts = System.currentTimeMillis();
          // mensagem de presenca padrao
          // formato: TIPO & INTERVALO & IP & TIMESTAMP
          String msg = papel + "&" + intervaloResponsavel + "&" + meuIp + "&" + ts;
          byte[] dados = msg.getBytes();

          // 1 Broadcast global (255.255.255.255)
          try {
            DatagramPacket pacoteGlobal = new DatagramPacket(dados, dados.length,
                InetAddress.getByName("255.255.255.255"), PORTA_SERVIDORES);
            socket.send(pacoteGlobal);
            if (DEBUG_PRESENCA)
              System.out.println("[DEBUG-BCAST] enviado global: " + msg);
          }//fim do try
          catch (Exception e) {
            if (DEBUG_PRESENCA)
              System.out.println("[DEBUG-BCAST] falha global: " + e.getMessage());
          }//fim do catchs

          // 2 Broadcast para cada broadcast address das interfaces
          try {
            // lista todas as interfaces de rede da maquina (ex: ethernet, wifi, virtuais)
            // necessario para enviar broadcast em cada interface que possui endereco de broadcast
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
              NetworkInterface ni = nics.nextElement();
              try {
                // ignora interfaces loopback ou inativas
                if (ni.isLoopback() || !ni.isUp())
                  continue;
              }//fim do try 
              catch (Exception ex) {
                continue;
              }//fim do catch
              for (InterfaceAddress ifAddr : ni.getInterfaceAddresses()) {
                InetAddress b = ifAddr.getBroadcast();
                if (b == null)
                  continue; // interface sem broadcast
                try {
                  DatagramPacket p = new DatagramPacket(dados, dados.length, b, PORTA_SERVIDORES);
                  socket.send(p);
                  if (DEBUG_PRESENCA)
                    System.out.println("[DEBUG-BCAST] enviado para " + b.getHostAddress());
                }//fim do try 
                catch (Exception ignored) {
                  // ignora erros por interface
                }//fim do catch
              }//fim do for
            }//fim do while
          }//fim do try 
          catch (Exception e) {
            if (DEBUG_PRESENCA)
              System.out.println("[DEBUG-BCAST] erro enumerando interfaces: " + e.getMessage());
          }//fim do catch

          // 3 Unicast para todos os servidores conhecidos do mesmo intervalo
          // (servidoresMesmoIntervalo eh um Set<String> contendo IPs)
          try {
            for (String ip : servidoresMesmoIntervalo) {
              if (ip == null || ip.isEmpty())
                continue;
              if (ip.equals(meuIp))
                continue; // nao envia para si mesmo
              try {
                DatagramPacket unicast = new DatagramPacket(dados, dados.length,
                    InetAddress.getByName(ip), PORTA_SERVIDORES);
                socket.send(unicast);
                if (DEBUG_PRESENCA)
                  System.out.println("[DEBUG-UNICAST] enviado para " + ip);
              }//fim do try
              catch (Exception ignored) {
                // ignora falha para um IP especifico e continua
              }//fim do catch
            }//fim do try

            // Tambehm garanta envio ao lider conhecido do intervalo (se houver e nao estiver
            // na lista)
            String lider = liderAtualPorIntervalo.get(intervaloResponsavel);
            if (lider != null && !lider.equals(meuIp) && !servidoresMesmoIntervalo.contains(lider)) {
              try {
                DatagramPacket pktL = new DatagramPacket(dados, dados.length,
                    InetAddress.getByName(lider), PORTA_SERVIDORES);
                socket.send(pktL);
                if (DEBUG_PRESENCA)
                  System.out.println("[DEBUG-UNICAST] enviado direto ao lider " + lider);
              }//fim do try 
              catch (Exception ignored) {
              }//fim do catch
            }//fim do if

          }//fim do try
          catch (Exception e) {
            if (DEBUG_PRESENCA)
              System.out.println("[DEBUG-UNICAST] erro no envio unicast: " + e.getMessage());
          }//fim do catch

          // pequena variacao aleatoria no intervalo
          // reduz colisoes entre mensagens simultaneas
          try {
            Thread.sleep(Math.max(100, INTERVALO_HELLO + rnd.nextInt(200)));
          }//fim do try
          catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }//fim do catch
        }//fim do while
      }//fim do try
      catch (Exception e) {
        e.printStackTrace();
      }//fim do catch
      finally {
        if (socket != null && !socket.isClosed()) {
          try {
            socket.close();
          }//fim do try
          catch (Exception ignore) {
          }//fim do catch
        }//fim do if
      }//fim do finally
    }, "Broadcast+Unicast-" + this.meuIp).start();
  }//fim do metodo iniciarBroadcastPresenca

  /* ***************************************************************
   * Metodo: iniciarEscutaPresenca
   * Funcao: escuta pacotes UDP de presenca, descobre lideres,
   *         atualiza listas de servidores ativos e dispara 
   *         sincronizacao de estado quando necessario.
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarEscutaPresenca() {
    new Thread(() -> {
      DatagramSocket socket = null;
      try {
        // cria socket de escuta na porta dos servidores
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORTA_SERVIDORES));

        byte[] buffer = new byte[65535];
        DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(pacote); // aguarda qualquer pacote de presenca
          String msg = new String(pacote.getData(), 0, pacote.getLength()).trim();

          // Pedido de descoberta de intervalos
          if (msg.startsWith("INTERVAL_DISCOVERY_REQUEST&")) {
            String resposta = "INTERVAL_DISCOVERY_RESPONSE&" +
                intervaloResponsavel + "&" + meuIp;
            byte[] dados = resposta.getBytes();
            DatagramPacket reply = new DatagramPacket(
                dados, dados.length,
                pacote.getAddress(),
                pacote.getPort());
            socket.send(reply);
            continue;
          }//fim do if

          /* --------------------------------------------------------
           * TRATAMENTO DE HELLO E HELLO_LIDER
           * Mensagens de presenca periodicas usadas para:
           *  - manter lista de servidores ativos
           *  - detectar lideres
           *  - acionar sincronizacao de estado
           * -------------------------------------------------------- */
          if (msg.startsWith("HELLO_LIDER&") || msg.startsWith("HELLO&")) {

            String[] partes = msg.split("&");
            if (partes.length < 3)
              continue;

            String papel = partes[0].trim(); // HELLO ou HELLO_LIDER
            String intervalo = partes[1].trim(); // ex: A-I
            String ip = partes[2].trim(); // IP do emissor

            // timestamp recebido (opcional)
            long ts = 0;
            if (partes.length >= 4) {
              try {
                ts = Long.parseLong(partes[3].trim());
              }//fim do try
              catch (Exception ignore) {
              }//fim do catch
            }//fim do if
            // obtem mapa de atividade correspondente ao intervalo
            Map<String, Long> mapaAtivos = servidoresAtivosPorIntervalo.get(intervalo);
            if (mapaAtivos == null)
              continue;

            long agora = System.currentTimeMillis();

            // Atualiza presenca
            mapaAtivos.put(ip, agora);

            // NOVO: Se eh do meu intervalo, adiciono para unicast
            if (intervalo.equals(this.intervaloResponsavel)) {
              servidoresMesmoIntervalo.add(ip);
            }//fim do if

            // PROCESSAMENTO DO HELLO_LIDER
            if (papel.equals("HELLO_LIDER")) {

              String atual = liderAtualPorIntervalo.get(intervalo);
              String conhecido = (atual == null) ? "NONE" : atual;

               // nenhum lider conhecido = aceita este
              if (conhecido.equals("NONE")) {
                liderAtualPorIntervalo.put(intervalo, ip);

                // se for meu intervalo, renova timeout
                if (intervalo.equals(this.intervaloResponsavel)) {
                  ultimoHelloLider = agora;
                  ciclosSemHelloLider = 0;
                }//fim do if

                System.out.println("Reconheco " + ip +
                    " como lider do intervalo " + intervalo + ".");
              }//fim do if

              else if (conhecido.equals(ip)) {
                // heartbeat do lider conhecido
                if (intervalo.equals(this.intervaloResponsavel)) {
                  ultimoHelloLider = agora;
                  ciclosSemHelloLider = 0;
                }//fim do if
              }//fim do else-if

              else {
                // HELLO_LIDER de outro servidor = ignorado
              }//fim do else

              // Se sou backup do meu intervalo = continuo como backup
              if (souBackup && intervalo.equals(this.intervaloResponsavel)) {
                this.souLider = false;
                continue;
              }//fim do if

              // Se ainda nao sincronizei = solicito estado ao lider
              if (!souLider &&
                  intervalo.equals(this.intervaloResponsavel) &&
                  !estadoSincronizado) {

                String leaderIp = liderAtualPorIntervalo.get(intervalo);
                if (leaderIp != null && !leaderIp.equals(meuIp)) {
                  solicitarEstadoDeOutroServidor(leaderIp);
                  estadoSincronizado = true;
                }//fim do if
              }//fim do if

              continue;
            }//fim do if

            // PROCESSAMENTO DO HELLO NORMAL
            else if (papel.equals("HELLO")) {
              // Se ainda nao conheco lider, recalculo
              if (liderAtualPorIntervalo.get(intervalo) == null) {
                recalcularLider(intervalo);
              }//fim do if
            }//fim do else-if
          }//fim do if
        }//fim do while

      }//fim do try 
      catch (Exception e) {
        e.printStackTrace();
      }//fim do catch
      finally {
        // fecha socket caso thread seja encerrada
        if (socket != null && !socket.isClosed()) {
          try {
            socket.close();
          }//fim do try
          catch (Exception ignore) {
          }//fim do catch
        }//fim do if
      }//fim do finally

    }, "EscutaPresenca-" + this.meuIp).start();
  }//fim do metodo iniciarEscutaPresenca

  /* ***************************************************************
   * Metodo: iniciarTimeoutMonitor
   * Funcao: Verifica periodicamente se algum servidor do intervalo
   *         parou de enviar HELLO e remove esse servidor da lista.
   *         Caso o lider falhe, aciona a promocao do backup.
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarTimeoutMonitor() {
    new Thread(() -> {
      while (true) {
        try {
          long agora = System.currentTimeMillis();

          // atraso de estabilizacao inicial antes de verificar timeouts
          if (agora - instanteInicial < 2 * TIMEOUT_SERVIDOR) {
            Thread.sleep(INTERVALO_TIMEOUT);
            continue;
          }//fim do if

          // Monitora apenas o intervalo que este servidor gerencia
          String intervalo = this.intervaloResponsavel;
          if (intervalo == null) {
            Thread.sleep(INTERVALO_TIMEOUT);
            continue;
          }//fim do if

          // obtem mapa de presenca do intervalo
          Map<String, Long> mapa = servidoresAtivosPorIntervalo.get(intervalo);
          if (mapa == null) {
            Thread.sleep(INTERVALO_TIMEOUT);
            continue;
          }//fim do if

          List<String> remover = new ArrayList<>();
          // verifica quem passou do timeout sem enviar HELLO
          for (Map.Entry<String, Long> entry : mapa.entrySet()) {
            long ultimoHello = entry.getValue();
            // se tempo sem HELLO passou do limite, falhou
            if (agora - ultimoHello > TIMEOUT_SERVIDOR) {
              remover.add(entry.getKey());
            }//fim do if
          }//fim do for

          // remove servidores inativos
          if (!remover.isEmpty()) {
            for (String ip : remover) {
              // nao remove a si mesmo mesmo que falhe temporariamente
              if (ip.equals(meuIp)) {
                // nao remove a si proprio
                mapa.put(ip, System.currentTimeMillis());
                continue;
              }//fim do if

              mapa.remove(ip);
              System.out.println("Servidor removido da lista (" + intervalo + "): " + ip);

              // Se o lider foi removido e este servidor e backup,
              // sinaliza para assumir lideranca
              if (ip.equals(liderAtualPorIntervalo.get(intervalo))
                  && intervalo.equals(this.intervaloResponsavel)
                  && this.souBackup) {
                
                liderAtualPorIntervalo.put(intervalo, "NONE");// zera lider conhecido para disparar eleicao
                ciclosSemHelloLider = CICLOS_TOLERADOS_SEM_LIDER; // forca promocao no proximo ciclo
                System.out
                    .println("Lider do intervalo " + intervalo + " foi removido. Preparando para assumir lideranca...");
              }//fim do if
            }//fim do for

            // recalcula lider apos remocao
            recalcularLider(intervalo);
          }//fim do if

          // caso o mapa fique completamente vazio (raro)
          if (mapa.isEmpty()) {
            recalcularLider(intervalo);
          }//fim do if

          // espera ate o proximo ciclo de verificacao
          Thread.sleep(INTERVALO_TIMEOUT);

        }//fim do try
        catch (Exception e) {
          e.printStackTrace();
        }//fim do catch
      }//fim do while
    }, "TimeoutMonitor-" + this.meuIp).start();
  }//fim do metodo iniciarTimeoutMonitor

  /* ***************************************************************
   * Metodo: recalcularLider
   * Funcao: define ou redefine o lider do intervalo conforme presença,
   *         timeout e regras de promocao do backup
   * Parametros: intervalo - intervalo gerenciado (ex: A-I)
   * Retorno: void
  *************************************************************** */
  private void recalcularLider(String intervalo) {
    boolean eraLiderLocal = this.souLider;
    String liderAnterior = liderAtualPorIntervalo.get(intervalo);
    Map<String, Long> mapaAtivos = servidoresAtivosPorIntervalo.get(intervalo);
    long agora = System.currentTimeMillis();

    // Evita recalcular se ja sou o lider reconhecido
    if (eraLiderLocal && meuIp.equals(liderAnterior)) {
      return;
    }//fim do if

    //  Logica de backup e promocao de lideranca 
    if (souBackup && intervalo.equals(this.intervaloResponsavel)) {
      long tempoSemHello = agora - ultimoHelloLider;

      // se passou mais de TIMEOUT_SERVIDOR, conta um ciclo sem HELLO
      if (tempoSemHello > TIMEOUT_SERVIDOR) {
        ciclosSemHelloLider++;
      }//fim do if
      else {
        ciclosSemHelloLider = 0; // lider respondeu
      }//fim do else

      // Se o lider falhou por varios ciclos, assume lideranca
      if (ciclosSemHelloLider >= CICLOS_TOLERADOS_SEM_LIDER) {
        System.out.println("Lider do intervalo " + intervalo + " inativo ha " +
            (ciclosSemHelloLider * TIMEOUT_SERVIDOR) + " ms. Backup assumindo lideranca.");

        this.souBackup = false;
        this.souLider = true;
        liderAtualPorIntervalo.put(intervalo, meuIp);
        ciclosSemHelloLider = 0;
        estadoSincronizado = false;

        System.out.println("Agora sou o lider do meu intervalo: " + intervaloResponsavel);

        // Se ja tenho estado local, replica
        if (!grupos.isEmpty()) {
          estadoSincronizado = true;
          System.out.println("Ja possuo estado local com " + grupos.size() + " grupos; replicando.");
          replicarEstrutura();
        }//fim do if 
        else {
          // tenta recuperar estado de outro servidor do mesmo intervalo
          if (mapaAtivos != null) {
            for (String ip : mapaAtivos.keySet()) {
              if (!ip.equals(meuIp)) {
                solicitarEstadoDeOutroServidor(ip);
                if (!grupos.isEmpty())
                  break;
              }//fim do if
            }//fim do for
          }//fim do if
          estadoSincronizado = true;
          replicarEstrutura();
        }//fim do else
      }//fim do if

      // backups nao participam da eleicao normal
      return;
    }//fim do if

    // Caso 1 = estou sozinho no intervalo 
    if (mapaAtivos == null || mapaAtivos.isEmpty()) {
      if (intervalo.equals(this.intervaloResponsavel)) {
        boolean eraLiderAntes = this.souLider;
        this.souLider = true;
        liderAtualPorIntervalo.put(intervalo, meuIp);
        System.out.println("Sou o único servidor do intervalo " + intervalo + ", assumindo lideranca imediatamente.");

        if (!eraLiderAntes) {
          System.out.println("Agora sou o lider do meu intervalo: " + intervaloResponsavel);
          estadoSincronizado = false;

          // Se ja ha grupos locais, replica
          if (!grupos.isEmpty()) {
            estadoSincronizado = true;
            System.out.println("Ja possuo estado local com " + grupos.size() + " grupos; replicando.");
            replicarEstrutura();
          }//fim do if 
          else {
            // tenta buscar estado de outros (se houver)
            Map<String, Long> mapa = servidoresAtivosPorIntervalo.get(intervalo);
            if (mapa != null) {
              for (String ip : mapa.keySet()) {
                if (!ip.equals(meuIp)) {
                  solicitarEstadoDeOutroServidor(ip);
                  if (!grupos.isEmpty())
                    break;
                }//fim do if
              }//fim do for
            }//fim do if
            estadoSincronizado = true;
            replicarEstrutura();
          }//fim do else
        }//fim do if
      }//fim do if
      return;
    }//fim do if

    // Caso 2 = ha servidores ativos, verifica se o lider esta vivo
    boolean liderInativo = false;
    if (liderAnterior == null || liderAnterior.equals("NONE")) {
      liderInativo = true;
    }//fim do if 
    else {
      Long ultimaAtividade = mapaAtivos.get(liderAnterior);
      if (ultimaAtividade == null || (agora - ultimaAtividade) > TIMEOUT_SERVIDOR * CICLOS_TOLERADOS_SEM_LIDER) {
        liderInativo = true;
      }//fim do if
    }//fim do else

    // Caso 3 = lider inativo - eleicao normal (menor IP vence)
    if (liderInativo) {
      String novoLider = null;
      for (String ip : mapaAtivos.keySet()) {
        if (novoLider == null || ip.compareTo(novoLider) < 0) {
          novoLider = ip;
        }//fim do if
      }//fim do for

      if (novoLider != null) {
        liderAtualPorIntervalo.put(intervalo, novoLider);
        // se eu fui eleito
        if (novoLider.equals(meuIp)) {
          this.souLider = true;
          if (!eraLiderLocal) {
            System.out.println("Fui eleito novo lider do intervalo " + intervalo + ".");
            estadoSincronizado = false;
             // replica ou busca estado
            if (!grupos.isEmpty()) {
              estadoSincronizado = true;
              System.out.println("Ja possuo estado local com " + grupos.size() + " grupos; replicando.");
              replicarEstrutura();
            }//fim do if 
            else {
              for (String ip : mapaAtivos.keySet()) {
                if (!ip.equals(meuIp)) {
                  solicitarEstadoDeOutroServidor(ip);
                  if (!grupos.isEmpty())
                    break;
                }//fim do if
              }//fim do for
              estadoSincronizado = true;
              replicarEstrutura();
            }//fim do else
          }//fim do if
        }//fim do if 
        else {
          // Se outro servidor virou lider
          this.souLider = false;
          if (eraLiderLocal) {
            System.out.println("Perdi a lideranca do intervalo " + intervalo + " para " + novoLider + ".");
          }//fim do if
        }//fim do else
      }//fim do if
    }//fim do if
  }//fim do metodo recalcularLider

  /* ***************************************************************
   * Metodo: iniciarDiscovery
   * Funcao: escuta pedidos DISCOVERY_REQUEST e responde informando
   *         quais intervalos este servidor lidera
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarDiscovery() {
    new Thread(() -> {
      // abre socket UDP na porta de discovery
      try (DatagramSocket socket = new DatagramSocket(PORTA_DISCOVERY)) {
        byte[] buffer = new byte[1024];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(request); // aguarda qualquer DISCOVERY_REQUEST enviado na rede
          // converte o conteudo recebido
          String msg = new String(request.getData(), 0, request.getLength()).trim();
          if (msg.equals("DISCOVERY_REQUEST")) {
            // monta resposta contendo todos os intervalos onde eu sou lider
            // (normalmente so ha 1 intervalo por servidor)
            StringBuilder sb = new StringBuilder();
            sb.append("DISCOVERY_RESPONSE");
            // Verifica todos os intervalos do sistema
            for (String intervalo : INTERVALOS) {
              String lid = liderAtualPorIntervalo.get(intervalo);
              if (lid != null && lid.equals(meuIp)) {
                sb.append("&").append(intervalo).append("&").append(meuIp);
              }//fim do if
            }//fim do for
            
            // responde somente se realmente lidero algum intervalo
            // (evita resposta inutil de servidores nao lideres)
            if (sb.length() > "DISCOVERY_RESPONSE".length()) {
              byte[] dados = sb.toString().getBytes();
              // envia unicast de volta para quem fez o request
              DatagramPacket reply = new DatagramPacket(dados, dados.length, request.getAddress(), request.getPort());
              socket.send(reply);
            }//fim do if
          }//fim do if
        }//fim do while
      }//fim do try
      catch (Exception e) {
        e.printStackTrace();
      }//fim do catch
    }).start();
  } // fim do metodo iniciarDiscovery

  /* ***************************************************************
   * Metodo: iniciarReplicacao
   * Funcao: recebe atualizacoes de estado enviadas pelo lider e
   *         sincroniza a estrutura de grupos local
   * Parametros: nenhum
   * Retorno: nenhum
  *************************************************************** */
  private void iniciarReplicacao() {
    new Thread(() -> {
      // Abre socket UDP para receber pacotes de replicacao
      try (DatagramSocket socket = new DatagramSocket(PORTA_REPLICACAO)) {
        byte[] buffer = new byte[65535];
        DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(pacote); // Aguarda pacote de replicacao

          // nao processe replicacao se eu for lider (eu envio, nao recebo)
          if (this.souLider)
            continue;
          // copia os bytes para desserializacao
          byte[] dadosPacote = Arrays.copyOf(pacote.getData(), pacote.getLength());
          try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dadosPacote))) {
            Object obj = ois.readObject(); // O lider envia um Map<String, List<Usuario>>
            // verifica formato
            if (!(obj instanceof Map)) { 
              // formato inesperado ignora
              continue;
            }//fim do if

            // Cria mapa filtrado contendo somente grupos do meu intervalo
            Map<String, List<Usuario>> novaEstrutura = (Map<String, List<Usuario>>) obj;

            // filtra apenas chaves do meu intervalo Responsavel
            Map<String, List<Usuario>> filtrada = new HashMap<>();
            for (Map.Entry<String, List<Usuario>> en : novaEstrutura.entrySet()) {
              if (grupoPertenceAoIntervalo(en.getKey(), this.intervaloResponsavel)) {
                // inclui apenas grupos permitidos ao meu intervalo
                filtrada.put(en.getKey(), en.getValue());
              }//fim do if
            }//fim do for

            // Se depois do filtro nao sobrou nada e eu ja tenho estado local, ignora
            if ((filtrada == null || filtrada.isEmpty()) && !grupos.isEmpty()) {
              // evita sobrescrever estado valido com estado vazio
              continue;
            }//fim do if

            // atualiza apenas os grupos permitidos ao meu intervalo
            // (mantem grupos locais de outros intervalos por seguranca)
            for (Map.Entry<String, List<Usuario>> en : filtrada.entrySet()) {
              grupos.put(en.getKey(), Collections.synchronizedList(new ArrayList<>(en.getValue())));
            }//fim do for

            // log simples indicando atualizacao
            System.out.println("Estado atualizado via replicacao (filtrado). Grupos: " +
                contagemFiltrada(grupos, this.intervaloResponsavel));
          }//fim do try
          catch (Exception e) {
            // pacote invalido ou erro de desserializacao = ignora
          }//fim do catch
        } // while
      }//fim do try 
      catch (Exception e) {
        e.printStackTrace();
      }//fim do catch
    }).start();
  }//fim do metodo iniciarReplicacao

  /* ***************************************************************
   * Metodo: contagemFiltrada
   * Funcao: conta quantos grupos pertencem ao intervalo indicado
   * Parametros: map = estrutura completa de grupos
                 intervalo = intervalo que deve ser considerado
   * Retorno: quantidade de grupos que pertencem ao intervalo
   *************************************************************** */
  private int contagemFiltrada(Map<String, List<Usuario>> mapa, String intervalo) {
    if (mapa == null) // Se o mapa for nulo, retorna zero
      return 0;
    int c = 0;
    // percorre todos os nomes de grupos
    for (String g : mapa.keySet())
      if (grupoPertenceAoIntervalo(g, intervalo))
        c++;
    return c; // retorna total de grupos do intervalo
  }//fim do metodo contagemFiltrada

  /* ***************************************************************
   * Metodo: iniciarReplicacaoPeriodica
   * Funcao: dispara um thread que replica o estado para os backups
   *         em intervalos regulares
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarReplicacaoPeriodica() {
    new Thread(() -> {
      while (true) {
        try {
          // Somente o lider envia replicacao
          // e somente quando o estado ja estiver sincronizado
          if (souLider && estadoSincronizado) {
            replicarEstrutura();
          }
          Thread.sleep(5000);
        }//fim do try 
        catch (Exception e) {
          // qualquer erro na replicacao nao interrompe o loop
          e.printStackTrace();
        }//fim do catch
      }//fim do while
    }).start();
  }// fim do metodo iniciarReplicacaoPeriodica

  /* ***************************************************************
   * Metodo: replicarEstrutura
   * Funcao: envia a estrutura de grupos do intervalo liderado
  *         para todos os servidores do mesmo intervalo
  *         usando broadcast UDP
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void replicarEstrutura() {
    if (!souLider) // Apenas o lider pode replicar estado
      return;
    try {
      // mapa que sera enviado: somente grupos pertencentes ao intervalo
      Map<String, List<Usuario>> toSend = new HashMap<>();
      // filtra grupos do intervalo do qual eu sou responsavel
      for (Map.Entry<String, List<Usuario>> e : grupos.entrySet()) {
        String grupo = e.getKey();
        // inclui apenas grupos cujo nome pertence ao meu intervalo
        if (grupoPertenceAoIntervalo(grupo, this.intervaloResponsavel)) {
          toSend.put(grupo, e.getValue());
        }//fim do if
      }//fim do for

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(bos);
      oos.writeObject(toSend);
      oos.flush();
      byte[] dados = bos.toByteArray();

      // envia para 255.255.255.255 na porta de replicacao
      // todos os servidores ouvindo essa porta receberao a nova estrutura
      DatagramSocket socket = new DatagramSocket();
      DatagramPacket pacote = new DatagramPacket(dados, dados.length,
          InetAddress.getByName("255.255.255.255"), PORTA_REPLICACAO);
      socket.send(pacote);
      socket.close();

    }//fim do try 
    catch (Exception e) {
      e.printStackTrace();
    }//fim do catch
  }// fim do metodo replicarEstrutura

  /* ***************************************************************
   * Metodo: solicitarEstadoDeOutroServidor
   * Funcao: solicita via TCP a estrutura completa de grupos de outro
   *         servidor do mesmo intervalo, aplicando filtro local
   * Parametros: ipAlvo - endereco IP do servidor alvo
   * Retorno: nenhum
   *************************************************************** */
  private void solicitarEstadoDeOutroServidor(String ipAlvo) {
    // ignora chamadas invalidas ou auto-chamada
    if (ipAlvo == null || ipAlvo.equals(meuIp))
      return;

    // tenta varias vezes recuperar o estado do servidor alvo
    for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_ESTADO; tentativa++) {
      try (Socket socket = new Socket()) {
        System.out.println("Tentativa " + tentativa + " de solicitar estado a " + ipAlvo);
        // abre conexao TCP com timeout
        socket.connect(new InetSocketAddress(ipAlvo, PORTA_CLIENTE), TIMEOUT_REQUEST_STATE);
        // envia comando de requisicao de estado
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println("REQUEST_STATE");
        // prepara leitura da resposta
        BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
        // marca posicao inicial para verificar primeiro byte da resposta
        bis.mark(10);
        int b1 = bis.read();
        bis.reset();

        // caso resposta comece com 'N', significa "NAO POSSUO ESTADO"
        if (b1 == 'N') {
          BufferedReader entrada = new BufferedReader(new InputStreamReader(bis));
          String resposta = entrada.readLine();
          System.out.println("Servidor " + ipAlvo + " respondeu: " + resposta);
          return;
        }//fim do if

        // le objeto serializado contendo o mapa de grupos
        ObjectInputStream ois = new ObjectInputStream(bis);
        Map<String, List<Usuario>> novaEstrutura = (Map<String, List<Usuario>>) ois.readObject();

        // verifica se estrutura recebida possui dados
        if (novaEstrutura != null && !novaEstrutura.isEmpty()) {
          // filtra apenas grupos pertencentes ao meu intervalo
          Map<String, List<Usuario>> filtrada = new HashMap<>();
          for (Map.Entry<String, List<Usuario>> en : novaEstrutura.entrySet()) {
            if (grupoPertenceAoIntervalo(en.getKey(), this.intervaloResponsavel)) {
              filtrada.put(en.getKey(), en.getValue());
            }//fim do if
          }//fim do for
          grupos = new ConcurrentHashMap<>(filtrada); // atualiza estrutura local com grupos filtrados
          estadoSincronizado = true;
          System.out.println("Estado recuperado de " + ipAlvo + " com " + grupos.size() + " grupos.");
         
          // se eu sou lider, replico o estado imediatamente
          if (souLider) {
            System.out.println("Sou lider e recuperei estado — replicando para o cluster.");
            replicarEstrutura();
          }//fim do if
          return; // estado recuperado com sucesso
        }//fim do if
        else {
          // outro servidor nao possui informacoes de grupos
          System.out.println("Servidor " + ipAlvo + " possuia estado vazio.");
        }//fim do else
      }//fim do try 
      catch (Exception e) {
        // Falha na tentativa atual, aguarda para tentar novamente
        System.out
            .println("Falha na tentativa " + tentativa + " ao recuperar estado de " + ipAlvo + ": " + e.getMessage());
        try {
          Thread.sleep(1000);
        }//fim do try 
        catch (InterruptedException ie) {
          // ignorado
        }//fim do catch
      }//fim do catch
    }//fim do for
    // Se chegou aqui, todas as tentativas falharam
    System.out
        .println("Nao foi possivel recuperar estado de " + ipAlvo + " apos " + MAX_TENTATIVAS_ESTADO + " tentativas.");
  }// fim do metodo solicitarEstadoDeOutroServidor

  class TratadorTCP extends Thread {
    private Socket cliente;

    public TratadorTCP(Socket cliente) {
      this.cliente = cliente;
    }//fim do construtor

    public void run() {
      try {
        BufferedReader entrada = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
        BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(cliente.getOutputStream()));

        String linha;
        while ((linha = entrada.readLine()) != null) {
          if (linha.equals("REQUEST_STATE")) {
            // Qualquer servidor pode devolver o estado que tem localmente (apenas grupos do
            // seu intervalo)
            ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
            oos.writeObject(grupos);
            oos.flush();
            System.out.println("Estado enviado a pedido de " + cliente.getInetAddress());
            break;
          }//fim do if 
          else {
            // Se a mensagem e de cliente (JOIN/LEAVE), deve-se verificar se o grupo
            // pertence ao intervalo que este servidor gerencia.
            // Se nao pertence, responde "NAO_RESPONSAVEL".
            String[] partes = linha.split("&");
            String comando = partes.length > 0 ? partes[0] : "";
            String grupo = partes.length >= 3 ? partes[2] : (partes.length >= 2 ? partes[1] : null);
            if (grupo != null && !grupo.isEmpty()) {
              if (!grupoPertenceAoIntervalo(grupo, thisServidorIntervalo())) {
                // Nao e responsavel por este grupo
                saida.write("NAO_RESPONSAVEL");
                saida.newLine();
                saida.flush();
                continue;
              }//fim do if
            }//fim do if
            // Se for lider do intervalo processa, senao responde NAO_SOU_LIDER (mantendo
            // compatibilidade)
            if (souLider) {
              String resposta = processarMensagemTCP(linha, cliente.getInetAddress());
              saida.write(resposta);
              saida.newLine();
              saida.flush();
            }//fim do if
            else {
              saida.write("NAO_SOU_LIDER");
              saida.newLine();
              saida.flush();
            }//fim do else
          }//fim do else
        }//fim do while
        entrada.close();
        saida.close();
        cliente.close();
      }//fim do try
      catch (IOException e) {
        e.printStackTrace();
      }//fim do catch
    }//fim do metodo run
  } // fim TratadorTCP

  class EscutadorUDP extends Thread {
    private DatagramSocket socket;

    public EscutadorUDP(DatagramSocket socket) {
      this.socket = socket;
    }//fim do construtor

    public void run() {
      byte[] buffer = new byte[1024];
      DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

      while (true) {
        try {
          socket.receive(pacote);
          String mensagem = new String(pacote.getData(), 0, pacote.getLength());
          System.out.println("UDP recebido: " + mensagem);
          // processa apenas se for lider e se o grupo pertencer ao meu
          // intervaloResponsavel
          if (souLider) {
            // extraimos o nome do grupo para verificar
            if (mensagem.startsWith("SEND&")) {
              String[] partes = mensagem.split("&", 4);
              if (partes.length == 4) {
                String grupo = partes[2].trim();
                if (grupoPertenceAoIntervalo(grupo, Servidor.this.intervaloResponsavel)) {
                  processarMensagemSEND(mensagem);
                }//fim do if
                else {
                  // Nao e responsavel por este grupo; ignora
                }//fim do else
              }//fim do if
            }//fim do if
          }//fim do if
        }//fim do try
        catch (IOException e) {
          e.printStackTrace();
        }//fim do catch
      }//fim do while
    }//fim do metodo run
  } // fim da classe EscutadorUDP

  private String processarMensagemTCP(String mensagem, InetAddress ipCliente) {
    try {
      if (mensagem.startsWith("JOIN&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          // verifica se esse grupo pertence ao intervalo do servidor
          if (!grupoPertenceAoIntervalo(grupo, this.intervaloResponsavel)) {
            return "NAO_RESPONSAVEL";
          }//fim do if

          Usuario novoUsuario = new Usuario(usuario, ipCliente, PORTA_CLIENTE);

          // garante existencia da lista sincronizada do grupo
          grupos.putIfAbsent(grupo, Collections.synchronizedList(new ArrayList<>()));
          List<Usuario> lista = grupos.get(grupo);
          // adiciona somente se ainda nao existir
          synchronized (lista) {
            boolean jaExiste = false;
            for (Usuario u : lista) {
              if (u.getNome().equals(usuario)) {
                jaExiste = true;
                break;
              }//fim do if
            }//fim do for
            if (!jaExiste) {
              lista.add(novoUsuario);
              System.out.println(usuario + " entrou no grupo " + grupo);
            }//fim do if
          }//fim do synchronized
          replicarEstrutura();
          return "OK JOIN";
        }//fim do if
      }//fim do if
      else if (mensagem.startsWith("LEAVE&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          if (!grupos.containsKey(grupo)) {
            System.out.println("Grupo " + grupo + " nao encontrado para LEAVE de " + usuario);
            return "ERRO";
          }//fim do if
          boolean saiu = removerUsuarioDoGrupo(grupo, usuario, ipCliente);
          if (saiu) {
            System.out.println(usuario + " saiu do grupo " + grupo);
            replicarEstrutura();
            return "OK LEAVE";
          }//fim do if
          else {
            System.out.println("Usuario " + usuario + " nao estava no grupo " + grupo);
            return "ERRO";
          }//fim do else
        }//fim do if
      }//fim do else if
    }//fim do try
    catch (Exception e) {
      e.printStackTrace();
    }//fim do catch
    return "ERRO";
  } //fim do metodo processarMensagemTCP

  /* ***************************************************************
   * Metodo: removerUsuarioDoGrupo
   * Funcao: remove um usuario especifico de um grupo, caso exista
   * Parametros: grupo = nome do grupo
   *             nome = nome do usuario
   *             ip = endereco IP do usuario
   * Retorno: true se removeu, false caso contrario
   *************************************************************** */
  private boolean removerUsuarioDoGrupo(String grupo, String nome, InetAddress ip) {
    List<Usuario> lista = grupos.get(grupo);
    if (lista != null) {
      // sincroniza lista para evitar concorrencia
      synchronized (lista) {
        Iterator<Usuario> it = lista.iterator();
        boolean removido = false;
        // percorre usuarios procurando o que coincide com nome e IP
        while (it.hasNext()) {
          Usuario u = it.next();
          // verifica identificacao completa do usuario
          if (u.getNome().equals(nome) && u.getIp().equals(ip)) {
            it.remove(); // remocao segura com iterator
            removido = true;
          }//fim do if
        }//fim do while
        return removido;
      }//fim do synchronized
    }//fim do if
    return false; // grupo inexistente ou usuario nao encontrado
  }//fim do metodo removerUsuarioDoGrupo

  /* ***************************************************************
   * Metodo: processarMensagemSEND
   * Funcao: envia a mensagem recebida para todos os usuarios do grupo
   *         exceto para o proprio emissor
   * Parametros: mensagem = string no formato SEND&usuario&grupo&conteudo
   * Retorno: void
   *************************************************************** */
  private void processarMensagemSEND(String mensagem) {
    if (!mensagem.startsWith("SEND&"))
      return;

    String[] partes = mensagem.split("&", 4);
    if (partes.length != 4)
      return;

    String usuario = partes[1].trim();
    String grupo = partes[2].trim();
    String conteudo = partes[3].trim();

    String mensagemParaEnviar = "SEND&" + usuario + "&" + grupo + "&" + conteudo;

     // se o grupo nao existe, nao ha nada a fazer
    if (!grupos.containsKey(grupo))
      return;

    List<Usuario> usuarios = grupos.get(grupo);
    // sincroniza acesso a lista para evitar concorrencia
    synchronized (usuarios) {
      for (Usuario u : usuarios) {
        if (!u.getNome().equals(usuario)) {
          try {
            byte[] dados = mensagemParaEnviar.getBytes();
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, u.getIp(), u.getPortaUDP());
            DatagramSocket socketEnvio = new DatagramSocket();
            socketEnvio.send(pacote);
            socketEnvio.close();
          }//fim do try
          catch (IOException e) {
            e.printStackTrace();
          }//fim do catch
        }//fim do if
      }//fim do for
    }//fim do synchronized
  }//fim do metodo processarMensagemSEND

  /* ***************************************************************
   * Metodo: grupoPertenceAoIntervalo
   * Funcao: verifica se o nome do grupo pertence ao intervalo de letras
   * Parametros: grupo = nome do grupo
   *             intervalo = intervalo configurado (A-I, J-R, S-Z)
   * Retorno: true se pertence, false caso contrario
   *************************************************************** */
  private boolean grupoPertenceAoIntervalo(String grupo, String intervalo) {
    // valida parametros basicos
    if (grupo == null || grupo.isEmpty() || intervalo == null)
      return false;
    // primeira letra do grupo em caixa alta
    char c = Character.toUpperCase(grupo.charAt(0));
    if (intervalo.equals("A-I"))
      return c >= 'A' && c <= 'I';
    if (intervalo.equals("J-R"))
      return c >= 'J' && c <= 'R';
    // intervalo desconhecido
    if (intervalo.equals("S-Z"))
      return c >= 'S' && c <= 'Z';
    return false;
  }//fim do metodo grupoPertenceAoIntervalo

  private String thisServidorIntervalo() {
    return this.intervaloResponsavel;
  }//fim do metodo thisServidorIntervalo

} // fim classe Servidor
