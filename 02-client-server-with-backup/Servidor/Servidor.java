/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 03/10/2025
* Nome.............: Servidor
* Funcao...........: Controla conexoes TCP/UDP com clientes e
*                    sincroniza estado com outros servidores
*************************************************************** */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {

  private static final int PORTA_CLIENTE = 6789; // JOIN/LEAVE (TCP) e SEND (UDP)
  private static final int PORTA_DISCOVERY = 6790; // descoberta por clientes
  private static final int PORTA_SERVIDORES = 6791; // broadcast de presença
  private static final int PORTA_REPLICACAO = 6792; // replicacao do estado

  private static final int INTERVALO_HELLO = 1000; // ms - envio de HELLO
  private static final int TIMEOUT_SERVIDOR = 3000; // ms - sem HELLO = morto
  private static final int INTERVALO_TIMEOUT = 1000; // ms - checa inativos

  private static final int TIMEOUT_REQUEST_STATE = 3000; // ms - timeout de connect/request state
  private static final int MAX_TENTATIVAS_ESTADO = 3; // numero de tentativas de pedir estado

  private String meuIp;
  private volatile boolean souLider = false;
  private volatile String liderAtual = null;
  private volatile boolean estadoSincronizado = false;
  private volatile long instanteInicial = System.currentTimeMillis();

  // estrutura de grupos compartilhada thread-safe
  private Map<String, List<Usuario>> grupos = new ConcurrentHashMap<>();

  // lista de servidores ativos: IP -> último HELLO (timestamp)
  private Map<String, Long> servidoresAtivos = new ConcurrentHashMap<>();

  /* ***************************************************************
   * Metodo: main
   * Funcao: Metodo principal que cria uma instancia de Servidor e a inicia
   * Parametros: args - argumentos da linha de comando (nao utilizados)
   * Retorno: void
  *************************************************************** */
  public static void main(String[] args) {
    Servidor servidor = new Servidor();
    servidor.iniciar();
  }//fim do metodo main

  /* ***************************************************************
   * Metodo: iniciar
   * Funcao: Inicializa o servidor, descobre IP, inicia threads principais de 
   *         presenca, timeout, replicacao, discovery e cria sockets para 
   *         conexao com clientes (TCP e UDP)
   * Parametros:
   * Retorno: void
  *************************************************************** */
  public void iniciar() {
    try {
      meuIp = descobrirMeuIp();
      System.out.println("Servidor iniciado em " + meuIp);

      // inicializacao das threads principais
      iniciarBroadcastPresenca();
      iniciarEscutaPresenca();
      iniciarTimeoutMonitor();
      iniciarReplicacao();
      iniciarDiscovery();
      iniciarReplicacaoPeriodica();

      // sockets para clientes
      ServerSocket serverSocket = new ServerSocket(PORTA_CLIENTE);
      DatagramSocket udpSocket = new DatagramSocket(PORTA_CLIENTE);

      new EscutadorUDP(udpSocket).start(); // thread para escutar mensagens UDP SEND de clientes

      while (true) {
        Socket cliente = serverSocket.accept(); // bloqueia ate aceitar conexao
        new TratadorTCP(cliente).start();
      }//fim do while

    } catch (Exception e) {
      e.printStackTrace();
    }//fim do try-catch
  }//fim do metodo iniciar

  /* ***************************************************************
   * Metodo: descobrirMeuIp
   * Funcao: Descobre o endereco IP local da maquina usando conexao
   *         simulada com servidor externo (8.8.8.8). Caso falhe,
   *         retorna o endereco de loopback padrao.
   * Parametros:
   * Retorno: String - endereco IP local detectado
  *************************************************************** */
  private String descobrirMeuIp() {
    try {
      DatagramSocket socket = new DatagramSocket();
      socket.connect(InetAddress.getByName("8.8.8.8"), 10002); // nao envia nada
      String ip = socket.getLocalAddress().getHostAddress();
      socket.close();
      return ip;
    } catch (Exception e) {
      return "127.0.0.1"; // fallback caso nao consiga detectar
    }//fim do try-catch
  }// fim do metodo descobrirMeuIp

  /* ***************************************************************
   * Metodo: iniciarBroadcastPresenca
   * Funcao: Inicia thread responsavel por enviar mensagens periodicas
   *         de presenca (HELLO ou HELLO_LIDER) para os demais servidores
   * Parametros:
   * Retorno: void
  *************************************************************** */
  private void iniciarBroadcastPresenca() {
    new Thread(() -> {
      try (DatagramSocket socket = new DatagramSocket()) {
        socket.setBroadcast(true);
        while (true) {
          // mensagem depende se sou lider ou apenas participante
          String msg = (souLider ? "HELLO_LIDER&" : "HELLO&") + meuIp;
          byte[] dados = msg.getBytes();
          DatagramPacket pacote = new DatagramPacket(
              dados, dados.length,
              InetAddress.getByName("255.255.255.255"), PORTA_SERVIDORES);
          socket.send(pacote); // envia mensagem em broadcast
          Thread.sleep(INTERVALO_HELLO); // intervalo entre mensagens
        }
      } catch (Exception e) {
        e.printStackTrace();
      }//fim do try-catch
    }).start();
  } // fim do metodo iniciarBroadcastPresenca

  /* ***************************************************************
   * Metodo: iniciarEscutaPresenca
   * Funcao: Inicia thread que escuta mensagens de presenca (HELLO e 
   *         HELLO_LIDER) enviadas por outros servidores e atualiza a 
   *         lista de servidores ativos e a informacao sobre o lider
   * Parametros:
   * Retorno: void
  *************************************************************** */
  private void iniciarEscutaPresenca() {
    new Thread(() -> {
      try (DatagramSocket socket = new DatagramSocket(PORTA_SERVIDORES)) {
        byte[] buffer = new byte[1024];
        DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(pacote);
          String msg = new String(pacote.getData(), 0, pacote.getLength()).trim();

          if (msg.startsWith("HELLO_LIDER&")) {
            String ip = msg.split("&")[1];
            servidoresAtivos.put(ip, System.currentTimeMillis());

            // se o IP recebido nao eh o meu, aceito como lider
            if (!meuIp.equals(ip)) {
              if (!ip.equals(liderAtual)) {
                System.out.println("Reconheço " + ip + " como líder.");
              }//fim do if
              liderAtual = ip;
              souLider = false;
            }//fim do if

            // se nao sou lider e ainda nao sincronizei estado, pedir ao lider
            if (!souLider && liderAtual.equals(ip) && !estadoSincronizado) {
              solicitarEstadoDeOutroServidor(liderAtual);
              estadoSincronizado = true;
            }//fim do if
          }//fim do if

          else if (msg.startsWith("HELLO&")) {
            String ip = msg.split("&")[1];
            servidoresAtivos.put(ip, System.currentTimeMillis());

            // apenas recalcula se ainda nao ha lider definido
            if (liderAtual == null) {
              recalcularLider();
            }//fim do if
          }//fim do else if
        }//fim do while
      } catch (Exception e) {
        e.printStackTrace();
      }//fim do try-catch
    }).start();
  }//fim do metodo iniciarEscutaPresenca

  /* ***************************************************************
   * Metodo: iniciarTimeoutMonitor
   * Funcao: Inicia thread que monitora os servidores ativos. Remove
   *         aqueles que nao enviaram HELLO dentro do tempo limite e
   *         aciona a reeleicao de lider quando necessario.
   * Parametros: 
   * Retorno: void
  *************************************************************** */
  private void iniciarTimeoutMonitor() {
    new Thread(() -> {
      while (true) {
        try {
          long agora = System.currentTimeMillis();
          List<String> remover = new ArrayList<>();

          // percorre lista de servidores e encontra os inativos
          for (Map.Entry<String, Long> entry : servidoresAtivos.entrySet()) {
            if (agora - entry.getValue() > TIMEOUT_SERVIDOR) {
              remover.add(entry.getKey());
            }//fim do if
          }//fim do for

          // remove os inativos
          if (!remover.isEmpty()) {
            for (String ip : remover) {
              servidoresAtivos.remove(ip);
              System.out.println("Servidor removido da lista: " + ip);
            }//fim do for
            recalcularLider();
          }//fim do if

          Thread.sleep(INTERVALO_TIMEOUT); // pausa entre verificacoes
        } catch (Exception e) {
          e.printStackTrace();
        }//fim do try-catch
      }//fim do while
    }).start();
  }// fim do metodo iniciarTimeoutMonitor

  /* ***************************************************************
   * Metodo: recalcularLider
   * Funcao: Determina qual servidor deve ser o lider do grupo. Respeita
   *         periodo de carencia apos inicio, remove lideres inativos,
   *         escolhe novo lider baseado no menor IP ativo e sincroniza 
   *         estado caso este servidor se torne lider.
   * Parametros:
   * Retorno: void
  *************************************************************** */
  private void recalcularLider() {
    boolean eraLider = souLider;

    // periodo de carencia apos inicio para evitar conflitos
    if (System.currentTimeMillis() - instanteInicial < 2 * TIMEOUT_SERVIDOR) {
      if (liderAtual == null) {
        System.out.println("Aguardando antes de assumir liderança...");
      }//fim do if
      souLider = false;
      return;
    }//fim do if

    // verifica se lider atual sumiu da lista de servidores ativos
    if (liderAtual == null || !servidoresAtivos.containsKey(liderAtual)) {
      if (liderAtual != null) {
        System.out.println("Líder anterior " + liderAtual + " está inativo.");
      }//fim do if
      liderAtual = null;
    }//fim do if

    // caso nao exista lider, escolhe novo com base no menor IP
    if (liderAtual == null) {
      // criterio: menor IP ativo
      String escolhido = null;
      for (String ip : servidoresAtivos.keySet()) {
        if (escolhido == null || ip.compareTo(escolhido) < 0) {
          escolhido = ip;
        }//fim do if
      }//fim do for
      if (escolhido == null) {
        escolhido = meuIp; // so eu existo
      }//fim do if
      liderAtual = escolhido;
      System.out.println("Novo líder eleito: " + liderAtual);
    }//fim do if

    // se lider existe e nao sou eu, continuo seguidor
    if (liderAtual != null && !liderAtual.equals(meuIp)) {
      souLider = false;
    }//fim do if
    else {
      souLider = meuIp.equals(liderAtual);
    }//fim do else

    // caso tenha assumido lideranca agora
    if (souLider && !eraLider) {
      System.out.println("Agora sou o líder.");
      estadoSincronizado = false;

      if (!grupos.isEmpty()) {
        // ja possuo estado local
        estadoSincronizado = true;
        System.out.println("Já possuo estado local com " + grupos.size() + " grupos; replicando.");
        replicarEstrutura();
      }//fim do if
      else {
        // tentar recuperar estado de outro, se houver
        for (String ip : servidoresAtivos.keySet()) {
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
    else if (!souLider && eraLider) { // caso tenha perdido a lideranca
      System.out.println("Perdi a liderança para " + liderAtual);
    }//fim do else if
  }//fim do metodo recalcularLider

  /* ***************************************************************
   * Metodo: iniciarDiscovery
   * Funcao: Inicia uma thread que escuta requisicoes de discovery de clientes
   *         e responde com seu IP apenas se for o servidor lider
   * Parametros:
   * Retorno: void
  *************************************************************** */
  private void iniciarDiscovery() {
    new Thread(() -> {
      try (DatagramSocket socket = new DatagramSocket(PORTA_DISCOVERY)) {
        byte[] buffer = new byte[1024];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(request);
          String msg = new String(request.getData(), 0, request.getLength()).trim();
          if (msg.equals("DISCOVERY_REQUEST") && souLider) {
            String resposta = "DISCOVERY_RESPONSE&" + meuIp;
            byte[] dados = resposta.getBytes();
            DatagramPacket reply = new DatagramPacket(
                dados, dados.length,
                request.getAddress(), request.getPort());
            socket.send(reply);
          }//fim do if
        }//fim do while
      } catch (Exception e) {
        e.printStackTrace();
      }//fim do try-catch
    }).start();
  }//fim do metodo iniciarDiscovery

  /* ***************************************************************
   * Metodo: iniciarReplicacao
   * Funcao: Inicia uma thread que escuta mensagens de replicacao vindas
   *         de outros servidores e atualiza a estrutura de grupos
   *         caso nao seja lider
   * Parametros:
   * Retorno: void
  *************************************************************** */
  private void iniciarReplicacao() {
    new Thread(() -> {
      try (DatagramSocket socket = new DatagramSocket(PORTA_REPLICACAO)) {
        byte[] buffer = new byte[65535];
        DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

        while (true) {
          socket.receive(pacote);

          // Se for lider ignora as mensagens de replicacao recebidas
          if (souLider)
            continue;

          try (ObjectInputStream ois = new ObjectInputStream(
              new ByteArrayInputStream(pacote.getData(), 0, pacote.getLength()))) {

            Map<String, List<Usuario>> novaEstrutura = (Map<String, List<Usuario>>) ois.readObject();

            // Se a estrutura recebida for vazia e eu ja tiver grupos locais
            // ignoro para nao sobrescrever meu estado valido
            if ((novaEstrutura == null || novaEstrutura.isEmpty()) && !grupos.isEmpty()) {
              System.out.println("Ignorando replicação vazia de " + pacote.getAddress().getHostAddress());
              continue;
            }//fim do if

            grupos = new ConcurrentHashMap<>(novaEstrutura);
            System.out.println("Estado atualizado via replicação. Grupos: " + grupos.size());
          } catch (Exception e) {
            e.printStackTrace();
          }//fim do try-catch
        }//fim do while
      } catch (Exception e) {
        e.printStackTrace();
      }//fim do try-catch
    }).start();
  }//fim do metodo iniciarReplicacao

  /* ***************************************************************
   * Metodo: iniciarReplicacaoPeriodica
   * Funcao: Inicia uma thread que envia periodicamente a estrutura
   *         de grupos para os outros servidores se for lider e
   *         o estado estiver sincronizado
   * Parametros: nenhum
   * Retorno: void
  *************************************************************** */
  private void iniciarReplicacaoPeriodica() {
    new Thread(() -> {
      while (true) {
        try {
          if (souLider && estadoSincronizado) {
            replicarEstrutura();
          }
          Thread.sleep(5000);
        } catch (Exception e) {
          e.printStackTrace();
        }//fim do try-catch
      }//fim do while
    }).start();
  }//fim do metodo iniciarReplicacao

  /* ***************************************************************
   * Metodo: replicarEstrutura
   * Funcao: Serializa a estrutura de grupos em bytes e envia em
   *         broadcast para todos os servidores
   * Parametros: nenhum
   * Retorno: void
  *************************************************************** */
  private void replicarEstrutura() {
    if (!souLider)
      return;
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(bos);
      oos.writeObject(grupos);
      oos.flush();
      byte[] dados = bos.toByteArray();

      DatagramSocket socket = new DatagramSocket();
      DatagramPacket pacote = new DatagramPacket(
          dados, dados.length,
          InetAddress.getByName("255.255.255.255"), PORTA_REPLICACAO);
      socket.send(pacote);
      socket.close();

    } catch (Exception e) {
      e.printStackTrace();
    }//fim do try-catch
  }//fim do metodo replicarEstrutura

  /* ***************************************************************
   * Metodo: solicitarEstadoDeOutroServidor
   * Funcao: Solicita o estado (estrutura de grupos) a outro servidor
   *         tentando varias vezes ate obter uma resposta valida
   * Parametros: ipAlvo - endereco IP do servidor alvo
   * Retorno: void
  *************************************************************** */
  private void solicitarEstadoDeOutroServidor(String ipAlvo) {
    // Se o ipAlvo for nulo ou for o proprio servidor, nao faz nada
    if (ipAlvo == null || ipAlvo.equals(meuIp))
      return;
    
    // Tenta recuperar o estado varias vezes ate atingir o limite MAX_TENTATIVAS_ESTADO
    for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_ESTADO; tentativa++) {
      try (Socket socket = new Socket()) {
        System.out.println("Tentativa " + tentativa + " de solicitar estado a " + ipAlvo);

        // Conecta ao servidor alvo na porta de cliente com um tempo maximo de espera
        socket.connect(new InetSocketAddress(ipAlvo, PORTA_CLIENTE), TIMEOUT_REQUEST_STATE);

        // Envia a mensagem especial de requisicao de estado
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println("REQUEST_STATE");

        // Usa BufferedInputStream para inspecionar a primeira resposta do servidor
        BufferedInputStream bis = new BufferedInputStream(socket.getInputStream()); 
        bis.mark(10); // marca a posicao para poder voltar depois
        int b1 = bis.read(); // le o primeiro byte da resposta
        bis.reset(); // encerra porque esse servidor nao tem o estado

        // Caso a resposta comece com 'N', significa que o servidor nao e lider
        if (b1 == 'N') {
          BufferedReader entrada = new BufferedReader(new InputStreamReader(bis));
          String resposta = entrada.readLine();
          System.out.println("Servidor " + ipAlvo + " respondeu: " + resposta);
          return;
        }//fim do if

        // Caso contrario, espera-se que o servidor envie a estrutura completa serializada
        ObjectInputStream ois = new ObjectInputStream(bis);
        Map<String, List<Usuario>> novaEstrutura = (Map<String, List<Usuario>>) ois.readObject();

        // Se recebeu uma estrutura valida e nao vazia, atualiza o estado local
        if (novaEstrutura != null && !novaEstrutura.isEmpty()) {
          grupos = new ConcurrentHashMap<>(novaEstrutura);
          estadoSincronizado = true;
          System.out.println("Estado recuperado de " + ipAlvo + " com " + grupos.size() + " grupos.");
          // Caso este servidor seja o lider apos recuperar o estado, ele replica para o cluster
          if (souLider) {
            System.out.println("Sou líder e recuperei estado — replicando para o cluster.");
            replicarEstrutura();
          }//fim do if
          return; // sucesso, sai do loop
        }//fim do if
        else {
          System.out.println("Servidor " + ipAlvo + " possuía estado vazio.");
        }//fim do else

      } catch (Exception e) {
        // Em caso de falha, informa o erro e espera 1 segundo antes de tentar de novo
        System.out
            .println("Falha na tentativa " + tentativa + " ao recuperar estado de " + ipAlvo + ": " + e.getMessage());
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
        }//fim do try-catch
      }//fim do try-catch
    }//fim do for
    
    // Caso todas as tentativas falhem, informa que nao foi possivel recuperar o estado
    System.out
        .println("Não foi possível recuperar estado de " + ipAlvo + " após " + MAX_TENTATIVAS_ESTADO + " tentativas.");
  }//fim do metodo solicitarEstadoDeOutroServidor


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
            // Qualquer servidor pode devolver o estado que tem localmente
            ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
            oos.writeObject(grupos);
            oos.flush();
            System.out.println("Estado enviado a pedido de " + cliente.getInetAddress());
            break;
          } else if (souLider) {
            String resposta = processarMensagemTCP(linha, cliente.getInetAddress());
            saida.write(resposta);
            saida.newLine();
            saida.flush();
          } else {
            saida.write("NAO_SOU_LIDER");
            saida.newLine();
            saida.flush();
          }//fim do if else if e else
        }//fim do while
        entrada.close();
        saida.close();
        cliente.close();
      } catch (IOException e) {
        e.printStackTrace();
      }//fim do try-catch
    }//fim do metodo run
  }//fim da classe TratadorTCP

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
          if (souLider) {
            processarMensagemSEND(mensagem);
          }
        } catch (IOException e) {
          e.printStackTrace();
        }//fim do try-catch
      }//fim do while
    }//fim do metodo run
  }//fim da classe


  /* ***************************************************************
   * Metodo: processarMensagemTCP
   * Funcao: Processa mensagens recebidas via conexao TCP de clientes.
   *         Atualmente trata os comandos JOIN e LEAVE para adicionar
   *         ou remover usuarios de grupos.
   * Parametros: 
   *   mensagem - texto enviado pelo cliente (JOIN ou LEAVE)
   *   ipCliente - endereco IP do cliente que enviou a mensagem
   * Retorno: String - resposta a ser enviada ao cliente (OK JOIN,
   *                   OK LEAVE ou ERRO)
*************************************************************** */
  private String processarMensagemTCP(String mensagem, InetAddress ipCliente) {
    try {
      if (mensagem.startsWith("JOIN&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          // Cria novo usuario com o IP e porta padrao
          Usuario novoUsuario = new Usuario(usuario, ipCliente, PORTA_CLIENTE);

          // Garante que o grupo exista, se nao existir cria uma lista sincronizada
          grupos.putIfAbsent(grupo, Collections.synchronizedList(new ArrayList<>()));
          List<Usuario> lista = grupos.get(grupo);

          // Bloqueia a lista para evitar condicao de corrida
          synchronized (lista) {
            boolean jaExiste = false;
            // Verifica se o usuario ja esta no grupo
            for (Usuario u : lista) {
              if (u.getNome().equals(usuario)) {
                jaExiste = true;
                break;
              }//fim do if
            }//fim do for
            // Se nao estiver presente, adiciona o novo usuario
            if (!jaExiste) {
              lista.add(novoUsuario);
              System.out.println(usuario + " entrou no grupo " + grupo);
            }//fim do if
          }//fim do synchronized
          replicarEstrutura(); // Replica a estrutura de grupos para outros servidores
          return "OK JOIN"; // Retorna confirmacao ao cliente
        }//fim do if
      } else if (mensagem.startsWith("LEAVE&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          // Verifica se o grupo existe
          if (grupos.containsKey(grupo)) {
            boolean saiu = removerUsuarioDoGrupo(grupo, usuario, ipCliente); // Tenta remover o usuario do grupo
            if (saiu) {
              System.out.println(usuario + " saiu do grupo " + grupo);
              replicarEstrutura();
              return "OK LEAVE";
            }//fim do if
            else {
              // Usuario nao estava no grupo
              System.out.println("Usuário " + usuario + " não estava no grupo " + grupo);
              return "ERRO";
            }//fim do else
          }//fim do if
          else {
            // Grupo nao encontrado
            System.out.println("Grupo " + grupo + " não encontrado para LEAVE de " + usuario);
            return "ERRO";
          }//fim do if e do else
        }//fim do if
      }//fim do else if
    } catch (Exception e) {
      e.printStackTrace();
    }//fim do try-catch
    return "ERRO";
  }//fim do metodo processarMensagemTCP

  /* ***************************************************************
   * Metodo: removerUsuarioDoGrupo
   * Funcao: Remove um usuario especifico de um grupo, verificando
   *         nome e endereco IP para garantir que seja o mesmo cliente
   * Parametros: grupo - nome do grupo do qual o usuario sera removido
   *             nome - nome do usuario a ser removido
   *             ip - endereco IP do usuario
   * Retorno: boolean - true se o usuario foi removido com sucesso, 
   *                    false caso contrario
  *************************************************************** */
  private boolean removerUsuarioDoGrupo(String grupo, String nome, InetAddress ip) {
    List<Usuario> lista = grupos.get(grupo);
    if (lista != null) {
      // Sincroniza o acesso a lista para evitar problemas de concorrencia
      synchronized (lista) {
        Iterator<Usuario> it = lista.iterator();
        boolean removido = false;
        // Percorre a lista procurando o usuario com mesmo nome e mesmo IP
        while (it.hasNext()) {
          Usuario u = it.next();
          if (u.getNome().equals(nome) && u.getIp().equals(ip)) {
            it.remove(); // remove com segurança usando o iterator
            removido = true;
          }//fim do if
        }//fim do while
        return removido;
      }//fim do synchronized
    }//fim do if
    return false;
  }//fim do metodo removerUsuarioDoGrupo

  /* ***************************************************************
   * Metodo: processarMensagemSEND
   * Funcao: Processa mensagens do tipo SEND recebidas via UDP. 
   *         Reenvia a mensagem para todos os membros do grupo, 
   *         exceto o remetente.
   * Parametros: 
   *   mensagem - string recebida no formato "SEND&usuario&grupo&conteudo"
   * Retorno: void
  *************************************************************** */
  private void processarMensagemSEND(String mensagem) {
    if (!mensagem.startsWith("SEND&"))
      return;

    // Divide a mensagem em 4 partes: comando, usuario, grupo e conteudo
    String[] partes = mensagem.split("&", 4);
    if (partes.length != 4)
      return;

    String usuario = partes[1].trim();
    String grupo = partes[2].trim();
    String conteudo = partes[3].trim();

    // Monta a mensagem padronizada para enviar aos membros
    String mensagemParaEnviar = "SEND&" + usuario + "&" + grupo + "&" + conteudo;

    // Se o grupo nao existe, nao faz nada
    if (!grupos.containsKey(grupo))
      return;

    List<Usuario> usuarios = grupos.get(grupo);
    synchronized (usuarios) {
      for (Usuario u : usuarios) {
        if (!u.getNome().equals(usuario)) {
          try {
            byte[] dados = mensagemParaEnviar.getBytes();
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, u.getIp(), u.getPortaUDP());
            // Cria um socket UDP temporario para enviar a mensagem
            DatagramSocket socketEnvio = new DatagramSocket();
            socketEnvio.send(pacote);
            socketEnvio.close();
          } catch (IOException e) {
            e.printStackTrace();
          }//fim do try-catch
        }//fim do if
      }//fim do for
    }//fim do synchronized
  }//fim do metodo processarMensagemSEND
}//fim da classe Servidor
