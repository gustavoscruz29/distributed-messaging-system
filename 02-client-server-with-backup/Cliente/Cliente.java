/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 02/10/2025
* Nome.............: Cliente
* Funcao...........: Representa o cliente da aplicacao de mensagens instantaneas,
*                    permitindo enviar e receber mensagens via UDP e comandos via TCP.
*                    Sempre redescobre o líder antes de cada operação.
*************************************************************** */

import java.io.*;
import java.net.*;

public class Cliente {

  private String nomeUsuario;
  private InetAddress ipServidor;
  private int portaServidor = 6789; // Porta fixa usada para comunicacao TCP e UDP com o servidor

  private DatagramSocket udpSocket; // Socket UDP para envio/recebimento de mensagens

  /*
   * ***************************************************************
   * Metodo: Cliente (construtor)
   * Funcao: Inicializa o cliente com nome e socket UDP
   * Parametros: nomeUsuario = nome do usuario
   * Retorno: nenhum
   */
  public Cliente(String nomeUsuario) throws Exception {
    this.nomeUsuario = nomeUsuario;
    this.udpSocket = new DatagramSocket(6789);
    atualizarLider(); // garante que começa já com o líder correto
  }// fim do construtor Cliente

  /*
   * ***************************************************************
   * Metodo: descobrirServidor
   * Funcao: Faz broadcast DISCOVERY_REQUEST e espera resposta do líder
   *************************************************************** */
  public static String descobrirServidor() throws IOException {
    while (true) {
      try (DatagramSocket socket = new DatagramSocket()) {
        socket.setSoTimeout(3000);

        String req = "DISCOVERY_REQUEST";
        byte[] dados = req.getBytes();
        DatagramPacket pacote = new DatagramPacket(
            dados, dados.length,
            InetAddress.getByName("255.255.255.255"), 6790);

        socket.setBroadcast(true);
        socket.send(pacote);

        byte[] buffer = new byte[1024];
        DatagramPacket resposta = new DatagramPacket(buffer, buffer.length);

        socket.receive(resposta); // espera ate 3s

        String msg = new String(resposta.getData(), 0, resposta.getLength()).trim();
        if (msg.startsWith("DISCOVERY_RESPONSE&")) {
          return msg.split("&")[1].trim();
        }
      } catch (SocketTimeoutException e) {
        System.out.println("Nenhum servidor encontrado... tentando novamente.");
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
      }
    }
  }

  /*
   * ***************************************************************
   * Metodo: atualizarLider
   * Funcao: Redescobre o IP do lider e atualiza ipServidor
   *************************************************************** */
  private void atualizarLider() throws IOException {
    String novoIp = descobrirServidor();
    this.ipServidor = InetAddress.getByName(novoIp);
    //System.out.println("Novo lider detectado: " + novoIp);
  }

  /*
   * ***************************************************************
   * Metodo: joinGrupo
   * Funcao: Solicita entrada em um grupo via TCP (sempre lider atual)
   *************************************************************** */
  public void joinGrupo(String nomeGrupo) {
    try {
      atualizarLider(); // garante que fala com o líder
      String mensagem = "JOIN&" + nomeUsuario + "&" + nomeGrupo;
      enviarTCP(mensagem);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }// fim do metodo joinGrupo

  /*
   * ***************************************************************
   * Metodo: leaveGrupo
   * Funcao: Solicita saída de um grupo via TCP (sempre líder atual)
   *************************************************************** */
  public void leaveGrupo(String nomeGrupo) {
    try {
      atualizarLider(); // garante que fala com o líder
      String mensagem = "LEAVE&" + nomeUsuario + "&" + nomeGrupo;
      enviarTCP(mensagem);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }// fim do metodo leaveGrupo

  /*
   * ***************************************************************
   * Metodo: enviarMensagem
   * Funcao: Envia mensagem para grupo via UDP (sempre líder atual)
   *************************************************************** */
  public void enviarMensagem(String nomeGrupo, String conteudo) throws IOException {
    atualizarLider(); // sempre fala com o líder
    String mensagem = "SEND&" + nomeUsuario + "&" + nomeGrupo + "&" + conteudo;
    byte[] dados = mensagem.getBytes();

    DatagramPacket pacote = new DatagramPacket(
        dados,
        dados.length,
        ipServidor,
        portaServidor);

    udpSocket.send(pacote);
  }// fim do metodo enviarMensagem

  /*
   * ***************************************************************
   * Metodo: iniciarRecebimento
   * Funcao: Thread para escutar mensagens UDP recebidas continuamente
   *************************************************************** */
  public void iniciarRecebimento(MensagemListener listener) {
    Thread thread = new Thread(() -> {
      byte[] buffer = new byte[1024];

      while (true) {
        try {
          DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
          udpSocket.receive(pacote); // Espera mensagem UDP
          String mensagem = new String(pacote.getData(), 0, pacote.getLength());
          listener.onMensagemRecebida(mensagem);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });

    thread.setDaemon(true); // Thread fecha junto com a aplicação
    thread.start();
  }// fim do metodo iniciarRecebimento

  /*
   * ***************************************************************
   * Metodo: enviarTCP
   * Funcao: Envia mensagem TCP ao líder e aguarda resposta
   *************************************************************** */
  private void enviarTCP(String mensagem) {
    try (Socket socket = new Socket(ipServidor, portaServidor);
         BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
         BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

      saida.write(mensagem);
      saida.newLine();
      saida.flush();

      String resposta = entrada.readLine();
      if (resposta != null) {
        System.out.println("Resposta servidor: " + resposta);
      }

    } catch (IOException e) {
      System.out.println("Falha ao enviar TCP. Tentando redescobrir...");
      try {
        atualizarLider();
        enviarTCP(mensagem); // tenta de novo com novo líder
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
  }// fim do metodo enviarTCP

  public int getPortaUDP() {
    return udpSocket.getLocalPort();
  }

  public String getNomeUsuario() {
    return nomeUsuario;
  }

  public void fechar() {
    if (udpSocket != null && !udpSocket.isClosed()) {
      udpSocket.close();
    }
  }
}// fim da classe Cliente
