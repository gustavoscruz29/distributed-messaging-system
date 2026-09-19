import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Cliente {

  private String nomeUsuario;
  private int portaServidor = 6789;
  private DatagramSocket udpSocket;  
  private final Map<String, InetAddress> lideres = new ConcurrentHashMap<>(); 

  /*
   * ***************************************************************
   * Metodo: Cliente (construtor)
   * Funcao: inicializa o cliente com um nome de usuario e cria o socket UDP
   * Parametros: nomeUsuario = nome do usuario que esta sendo registrado
   * Retorno: void
   *************************************************************** */
  public Cliente(String nomeUsuario) throws Exception {
    this.nomeUsuario = nomeUsuario;

    this.udpSocket = new DatagramSocket(portaServidor);
    this.udpSocket.setSoTimeout(0);

    iniciarDescobertaContinua();
  }//fim do construtor

  /* ***************************************************************
   * Metodo: iniciarDescobertaContinua
   * Funcao: Inicia um processo de descoberta de lideres via UDP
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void iniciarDescobertaContinua() {
    Thread t = new Thread(() -> {
      try (DatagramSocket ds = new DatagramSocket(6791)) {
        ds.setSoTimeout(0); // bloqueio
        // Define o timeout do socket como 0, o que significa bloqueio indefinido
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);

        while (true) {
          try {
            // Aguarda o recebimento de um pacote
            ds.receive(p);
            // Converte os dados recebidos em string e remove os espaços extras
            String msg = new String(p.getData(), 0, p.getLength()).trim();
            
             // Verifica se a mensagem recebida comeca com "HELLO_LIDER"
            if (msg.startsWith("HELLO_LIDER")) {
              String[] partes = msg.split("&");
              if (partes.length >= 3) {
                String intervaloRaw = partes[1].trim();
                String intervalo = normalizarIntervalo(intervaloRaw);
                String ipStr = partes[2].trim(); // Extrai o IP do lider
                try {
                  // Converte o IP de string para InetAddress
                  InetAddress ip = InetAddress.getByName(ipStr); 
                  lideres.put(intervalo, ip); // Armazena o lider no mapa de lideres
                }//fim do try
                catch (Exception ex) {
                }//fim do catch
              }//fim do if
            }//fim do if
          }//fim do try
          catch (IOException ioe) {
            ioe.printStackTrace();
          }//fim do catch
        }//fim do while true
      }//fim do try
      catch (SocketException se) {
        se.printStackTrace();
      }//fim do catch
    }, "Cliente-Descoberta-HELLO");
    t.setDaemon(true);
    t.start();
  }//fim do metodo iniciarDescobertaContinua

  /*
   * ***************************************************************
   * Metodo: normalizarIntervalo
   * Funcao: Normaliza o intervalo do grupo com base no nome fornecido
   * Parametros: raw = string com o nome do intervalo a ser normalizado
   * Retorno: String = intervalo normalizado ("AI", "JR" ou "SZ")
   *************************************************************** */
  private String normalizarIntervalo(String raw) {
    if (raw == null) return "SZ";
    String s = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    if (s.startsWith("AI") || s.startsWith("A")) return "AI";
    if (s.startsWith("JR") || s.startsWith("J")) return "JR";
    return "SZ";
  }//fim do metodo normalizarIntervalo

  /*
   * ***************************************************************
   * Metodo: intervaloDoGrupo
   * Funcao: retorna o intervalo do grupo baseado na primeira letra
   * Parametros: grupo = nome do grupo para o qual se deseja obter o intervalo
   * Retorno: String = intervalo do grupo ("AI", "JR" ou "SZ")
   *************************************************************** */
  private String intervaloDoGrupo(String grupo) {
    if (grupo == null || grupo.trim().isEmpty()) return "SZ";
    char c = Character.toUpperCase(grupo.trim().charAt(0));
    if (c >= 'A' && c <= 'I') return "AI";
    if (c >= 'J' && c <= 'R') return "JR";
    return "SZ";
  }//fim do metodo intervaloDoGrupo

  /* ***************************************************************
   * Metodo: getLiderParaGrupo
   * Funcao: retorna o IP do lider responsavel pelo grupo
   * Parametros: grupo = nome do grupo para o qual se deseja obter o lider
   * Retorno: InetAddress = IP do lider responsavel pelo grupo
   *************************************************************** */
  private InetAddress getLiderParaGrupo(String grupo) {
    String key = intervaloDoGrupo(grupo);
    return lideres.get(key);
  }//fim do metodo getLiderParaGrupo

  /* ***************************************************************
   * Metodo: joinGrupo
   * Funcao: Envia uma solicitacao para entrar em um grupo ao lider via TCP
   * Parametros: nomeGrupo = nome do grupo que o usuario deseja entrar
   * Retorno: void
   *************************************************************** */
  public void joinGrupo(String nomeGrupo) throws IOException {
    InetAddress ip = getLiderParaGrupo(nomeGrupo); // Obtem o IP do lider responsavel pelo grupo
    if (ip == null) {
      throw new IOException("Nenhum líder disponível para o intervalo do grupo: " + nomeGrupo);
    }//fim do if
    String mensagem = "JOIN&" + nomeUsuario + "&" + nomeGrupo;
    enviarTCP(ip, portaServidor, mensagem); // Envia a mensagem via TCP para o lider do grupo
  }//fim do metodo joinGrupo

  /* ***************************************************************
   * Metodo: leaveGrupo
   * Funcao: envia uma solicitacao para sair de um grupo ao lider via TCP
   * Parametros: nomeGrupo = nome do grupo do qual o usuario deseja sair
   * Retorno: void
   *************************************************************** */
  public void leaveGrupo(String nomeGrupo) throws IOException {
    InetAddress ip = getLiderParaGrupo(nomeGrupo); // Obtem o IP do lider responsavel pelo grupo
    if (ip == null) {
      throw new IOException("Nenhum líder disponível para o intervalo do grupo: " + nomeGrupo);
    }//fim do if
    String mensagem = "LEAVE&" + nomeUsuario + "&" + nomeGrupo;
    enviarTCP(ip, portaServidor, mensagem); // envia a mensagem via TCP para o lider do grupo
  }//fim do metodo leaveGrupo

  /* ***************************************************************
   * Metodo: enviarMensagem
   * Funcao: envia uma mensagem para o líder do grupo via UDP
   * Parametros: nomeGrupo = nome do grupo para o qual a mensagem sera enviada
                 conteudo = conteudo da mensagem a ser enviada
   * Retorno: void
   *************************************************************** */
  public void enviarMensagem(String nomeGrupo, String conteudo) throws IOException {
    InetAddress ip = getLiderParaGrupo(nomeGrupo); // obtem o IP do líder responsavel pelo grupo
    if (ip == null) {
      // lanca uma excecao caso nao haja lider disponivel para o grupo
      throw new IOException("Nenhum líder disponível para o intervalo do grupo: " + nomeGrupo);
    }//fim do if
    String mensagem = "SEND&" + nomeUsuario + "&" + nomeGrupo + "&" + conteudo;
    byte[] dados = mensagem.getBytes(); // converte a mensagem para um array de bytes

    // cria o pacote UDP com os dados, IP do lider e a porta do servidor
    DatagramPacket pacote = new DatagramPacket(dados, dados.length, ip, portaServidor);
    udpSocket.send(pacote);
  }//fim do metodo enviarMensagem

  /* ***************************************************************
   * Metodo: iniciarRecebimento
   * Funcao: inicia uma thread para receber mensagens UDP e notificar o listener
   * Parametros: listener = objeto que implementa a interface MensagemListener
   * Retorno: void
   *************************************************************** */
  public void iniciarRecebimento(MensagemListener listener) {
    Thread thread = new Thread(() -> {
      byte[] buffer = new byte[2048];
      DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
      while (true) {
        try {
          udpSocket.receive(pacote); // espera ate que uma mensagem seja recebida
          // converte os dados recebidos em uma string
          String mensagem = new String(pacote.getData(), 0, pacote.getLength());
          listener.onMensagemRecebida(mensagem); // chama o listener para processar a mensagem recebida
        }//fim do try 
        catch (SocketException se) {
          break; // Encerra a thread se o socket for fechado
        }//fim do catch
        catch (IOException e) {
          e.printStackTrace(); // exibe o erro no console caso ocorra uma exceção de IO
        }//fim do catch
      }//fim do while
    }, "Cliente-UDP-Receiver");
    thread.setDaemon(true);
    thread.start();
  }//fim do metodo iniciarRecebimento

  /*
   * ***************************************************************
   * Metodo: enviarTCP
   * Funcao: Envia uma mensagem via TCP para um servidor e recebe a resposta
   * Parametros: ip = endereco IP do servidor
                 porta = porta para conexao com o servidor
                 mensagem = mensagem a ser enviada
   * Retorno: void
   *************************************************************** */
  private void enviarTCP(InetAddress ip, int porta, String mensagem) throws IOException {
    try (Socket socket = new Socket(ip, porta);
         BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
         BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

      saida.write(mensagem);
      saida.newLine();
      saida.flush();

      String resposta = entrada.readLine();
      if (resposta != null) {
        System.out.println("Resposta servidor: " + resposta);
      }//fim do if
    }//fim do try
  }//fim do metodo enviarTCP

  public int getPortaUDP() {
    return udpSocket.getLocalPort();
  }//fim do metodo getPortaUDP

  public String getNomeUsuario() {
    return nomeUsuario;
  }//fim do metodo getNomeUsuario

  /* ***************************************************************
   * Metodo: fechar
   * Funcao: fecha o socket UDP se ele estiver aberto
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  public void fechar() {
    try {
      if (udpSocket != null && !udpSocket.isClosed()) {
        udpSocket.close();
      }//fim do if
    } catch (Exception ignored) {}
  }//fim do metodo fechar

}//fim da classe Cliente
