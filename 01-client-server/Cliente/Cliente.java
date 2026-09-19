/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 23/08/2025
* Nome.............: Cliente
* Funcao...........: Representa o cliente da aplicacao de mensagens instantaneas,
*                    permitindo enviar e receber mensagens via UDP e comandos via TCP
*************************************************************** */

import java.io.*;
import java.net.*;

public class Cliente {

  private String nomeUsuario;
  private InetAddress ipServidor;
  private int portaServidor = 6789; // Porta fixa usada para comunicacao TCP e UDP com o servidor

  private DatagramSocket udpSocket; // Socket UDP para envio/recebimento de mensagens

  /* ***************************************************************
  * Metodo: Cliente (construtor)
  * Funcao: Inicializa o cliente com nome e IP do servidor, criando o socket UDP
  * Parametros: nomeUsuario = nome do usuario
  *             ipServidor = endereco IP do servidor
  * Retorno: nenhum
  *************************************************************** */
  public Cliente(String nomeUsuario, String ipServidor) throws Exception {
    this.nomeUsuario = nomeUsuario;
    this.ipServidor = InetAddress.getByName(ipServidor);
    this.udpSocket = new DatagramSocket(6789); // Cria socket UDP
  }// fim do construtor Cliente

  /* ***************************************************************
  * Metodo: joinGrupo
  * Funcao: Solicita entrada em um grupo via TCP
  * Parametros: nomeGrupo = nome do grupo a entrar
  * Retorno: boolean indicando se a entrada foi bem-sucedida
  *************************************************************** */
  public void joinGrupo(String nomeGrupo) {
    String mensagem = "JOIN&" + nomeUsuario + "&" + nomeGrupo;
    enviarTCP(mensagem);
  }// fim do metodo joinGrupo

  /* ***************************************************************
  * Metodo: leaveGrupo
  * Funcao: Solicita saida de um grupo via TCP
  * Parametros: nomeGrupo = nome do grupo a sair
  * Retorno: boolean indicando se a saida foi bem-sucedida
  *************************************************************** */
  public void leaveGrupo(String nomeGrupo) {
    String mensagem = "LEAVE&" + nomeUsuario + "&" + nomeGrupo;
    enviarTCP(mensagem);
  }// fim do metodo leaveGrupo

  /* ***************************************************************
  * Metodo: enviarMensagem
  * Funcao: Envia uma mensagem para o grupo especificado via UDP
  * Parametros: nomeGrupo = nome do grupo
  *             conteudo = mensagem a ser enviada
  * Retorno: void
  *************************************************************** */
  public void enviarMensagem(String nomeGrupo, String conteudo) throws IOException {
    String mensagem = "SEND&" + nomeUsuario + "&" + nomeGrupo + "&" + conteudo;
    byte[] dados = mensagem.getBytes();

    DatagramPacket pacote = new DatagramPacket(
        dados,
        dados.length,
        ipServidor,
        portaServidor);

    udpSocket.send(pacote);
  }// fim do metodo enviarMensagem

  /* ***************************************************************
  * Metodo: iniciarRecebimento
  * Funcao: Cria uma thread para escutar mensagens UDP recebidas continuamente
  * Parametros: listener = interface para tratar mensagens recebidas
  * Retorno: void
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
        } // fim do try-catch
      } // fim do while
    });

    thread.setDaemon(true); // Permite que a thread finalize com a aplicacao
    thread.start();
  }// fim do metodo iniciarRecebimento

  /* ***************************************************************
  * Metodo: enviarTCP
  * Funcao: Envia uma mensagem TCP ao servidor e aguarda resposta
  * Parametros: mensagem = texto da mensagem (APDU) a ser enviada
  * Retorno: void
  *************************************************************** */
  private void enviarTCP(String mensagem) {
    try (Socket socket = new Socket(ipServidor, portaServidor);
        BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

      saida.write(mensagem);
      saida.newLine();
      saida.flush();

    } // fim do try
    catch (IOException e) {
      e.printStackTrace();
    } // fim do catch
  }// fim do metodo enviarTCP

  public int getPortaUDP() {
    return udpSocket.getLocalPort();
  }// fim do metodo getPortaUDP

  public String getNomeUsuario() {
    return nomeUsuario;
  }// fim do metodo getNomeUsuario
  
  public void fechar() {
    if (udpSocket != null && !udpSocket.isClosed()) {
      udpSocket.close();
    }//fim do if
  }//fim do metodo fechar
  
}// fim da classe Cliente
