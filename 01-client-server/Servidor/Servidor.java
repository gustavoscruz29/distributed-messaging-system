/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 23/08/2025
* Nome.............: Servidor
* Funcao...........: Controla conexoes TCP para JOIN/LEAVE e
*                    mensagens UDP para transmissao de mensagens em grupos
*************************************************************** */

import java.io.*;
import java.net.*;
import java.util.*;

public class Servidor {

  private static final int PORTA = 6789;

  // associa nomes de grupos a listas de usuarios
  private Map<String, List<Usuario>> grupos = Collections.synchronizedMap(new HashMap<>());


  public static void main(String[] args) {
    Servidor servidor = new Servidor();
    servidor.iniciar();
  }//fim do metodo main

  /* ***************************************************************
  * Metodo: iniciar
  * Funcao: Inicializa o servidor TCP e UDP para conexoes e escuta de mensagens
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  public void iniciar() {
    try {
      ServerSocket serverSocket = new ServerSocket(PORTA);
      DatagramSocket udpSocket = new DatagramSocket(PORTA);

      System.out.println("Servidor iniciado na porta " + PORTA);

      new EscutadorUDP(udpSocket).start();

      while (true) {
        Socket cliente = serverSocket.accept();
        System.out.println("Cliente TCP conectado: " + cliente.getInetAddress());

        new TratadorTCP(cliente).start(); 
      }//fim do while

    } catch (IOException e) {
      e.printStackTrace();
    }//fim do try-catch
  }//fim do metodo iniciar

  /* ***************************************************************
  * Classe: TratadorTCP
  * Funcao: Trata requisicoes TCP de um cliente (JOIN e LEAVE)
  *************************************************************** */
  class TratadorTCP extends Thread {
    private Socket cliente;

    public TratadorTCP(Socket cliente) {
      this.cliente = cliente;
    }//fim do construtor TratadorTCP

    /* ***************************************************************
    * Metodo: run
    * Funcao: Le mensagens TCP do cliente e processa JOIN/LEAVE
    * Parametros: nenhum
    * Retorno: void
    *************************************************************** */
    public void run() {
      try {
        BufferedReader entrada = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
        BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(cliente.getOutputStream()));

        String linha;
        while ((linha = entrada.readLine()) != null) {
          System.out.println("TCP recebido: " + linha);
          String resposta = processarMensagemTCP(linha, cliente.getInetAddress());
          if (resposta != null) {
            saida.write(resposta);
            saida.newLine();
            saida.flush();
          }//fim do if
        }//fim do while

        entrada.close();
        saida.close();
        cliente.close();

      } catch (IOException e) {
        e.printStackTrace();
      }//fim do try-catch
    }//fim do metodo run
  }//fim da classe TratadorTCP

  /* ***************************************************************
  * Classe: EscutadorUDP
  * Funcao: Escuta e processa mensagens UDP (SEND)
  *************************************************************** */
  class EscutadorUDP extends Thread {
    private DatagramSocket socket;

    public EscutadorUDP(DatagramSocket socket) {
      this.socket = socket;
    }//fim do construtor EscutadorUDP

    /* ***************************************************************
    * Metodo: run
    * Funcao: Espera por pacotes UDP e repassa para o metodo de processamento
    * Parametros: nenhum
    * Retorno: void
    *************************************************************** */
    public void run() {
      byte[] buffer = new byte[1024];
      DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

      while (true) {
        try {
          socket.receive(pacote);
          String mensagem = new String(pacote.getData(), 0, pacote.getLength());
          System.out.println("UDP recebido: " + mensagem);

          processarMensagemSEND(mensagem);

        } catch (IOException e) {
          e.printStackTrace();
        }//fim do try-catch
      }//fim do while
    }//fim do metodo run
  }//fim da classe EscutadorUDP
  
  /* ***************************************************************
  * Metodo: processarMensagemTCP
  * Funcao: Trata comandos JOIN e LEAVE recebidos via TCP
  * Parametros: mensagem = comando TCP
                ipCliente = IP do cliente
  * Retorno: String - resposta ao cliente ("OK JOIN", "OK LEAVE" ou "ERRO")
  *************************************************************** */
  private String processarMensagemTCP(String mensagem, InetAddress ipCliente) {
    try {
      if (mensagem.startsWith("JOIN&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          Usuario novoUsuario = new Usuario(usuario, ipCliente, PORTA);
          grupos.putIfAbsent(grupo, Collections.synchronizedList(new ArrayList<>()));

          List<Usuario> lista = grupos.get(grupo);
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
          return "OK JOIN";
        }//fim do if
      }//fim do if
      else if (mensagem.startsWith("LEAVE&")) {
        String[] partes = mensagem.split("&");
        if (partes.length == 3) {
          String usuario = partes[1].trim();
          String grupo = partes[2].trim();

          if (grupos.containsKey(grupo)) {
            removerUsuarioDoGrupo(grupo, usuario, ipCliente);
            System.out.println(usuario + " saiu do grupo " + grupo);
            return "OK LEAVE";
          }//fim do if
        }//fim do if
      }//fim do else if

    } catch (Exception e) {
      e.printStackTrace();
    }//fim do try-catch
    return "ERRO";
  }//fim do metodo processarMensagemTCP

  /* ***************************************************************
  * Metodo: removerUsuarioDoGrupo
  * Funcao: Remove um usuario da lista do grupo especificado
  * Parametros: grupo = nome do grupo;
  *             nome = nome do usuario;
  *             ip = IP do usuario
  * Retorno: void
  *************************************************************** */
  private void removerUsuarioDoGrupo(String grupo, String nome, InetAddress ip) {
    List<Usuario> lista = grupos.get(grupo);
    synchronized (lista) {
      for (int i = lista.size() - 1; i >= 0; i--) {
        Usuario u = lista.get(i);
        if (u.getNome().equals(nome) && u.getIp().equals(ip)) {
          lista.remove(i);
          break;
        }//fim do if
      }//fim do for
    }//fim do synchronized
  }//fim do metodo removerUsuarioDoGrupo

  /* ***************************************************************
  * Metodo: processarMensagemSEND
  * Funcao: Trata mensagens UDP enviadas pelos clientes e as retransmite
  *         para todos os membros do grupo, exceto o remetente
  * Parametros: mensagem = mensagem recebida no formato: "SEND grupo, usuario, conteudo"
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

    if (!grupos.containsKey(grupo))
      return;

    List<Usuario> usuarios = grupos.get(grupo);
    synchronized (usuarios) {
      for (Usuario u : usuarios) {
        if (!u.getNome().equals(usuario)) {
          try {
            byte[] dados = mensagemParaEnviar.getBytes();
            DatagramPacket pacote = new DatagramPacket(dados, dados.length, u.getIp(), u.getPortaUDP());
            DatagramSocket socketEnvio = new DatagramSocket();
            socketEnvio.send(pacote);
            socketEnvio.close();
          } catch (IOException e) {
            e.printStackTrace();
          }//fim do try-catch
        }//fim do if
      }//fim do for
    }//fim do synchronized
  }//fim do metodo processarMensagemSend
}//fim da classe Servidor

