/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 04/07/2025
* Nome.............: Usuario
* Funcao...........: Representa um usuario conectado a um grupo, 
*                    armazenando seu nome, IP e porta UDP
*************************************************************** */

import java.net.InetAddress;
import java.io.Serializable;

public class Usuario implements Serializable {
  private static final long SerialVersionUID = 1L;
  private String nome;
  private InetAddress ip;
  private int portaUDP;

  /* ***************************************************************
  * Metodo: Usuario (construtor)
  * Funcao: Inicializa um novo usuario com nome, IP e porta UDP
  * Parametros: nome = nome do usuario
                ip = endereco IP do usuario
                portaUDP = porta UDP usada para receber mensagens
  * Retorno: nenhum
  *************************************************************** */
  public Usuario(String nome, InetAddress ip, int portaUDP) {
    this.nome = nome;
    this.ip = ip;
    this.portaUDP = portaUDP;
  }// fim do construtor Usuario

  /* ***************************************************************
  * Metodo: getNome
  * Funcao: Retorna o nome do usuario
  * Parametros: nenhum
  * Retorno: nome = nome do usuario (String)
  *************************************************************** */
  public String getNome() {
    return nome;
  }// fim do metodo getNome

  /* ***************************************************************
  * Metodo: getIp
  * Funcao: Retorna o endereco IP do usuario
  * Parametros: nenhum
  * Retorno: ip = IP do usuario (InetAddress)
  *************************************************************** */
  public InetAddress getIp() {
    return ip;
  }// fim do getIP

  /* ***************************************************************
  * Metodo: getPortaUDP
  * Funcao: Retorna a porta UDP que o usuario utiliza para receber mensagens
  * Parametros: nenhum
  * Retorno: int - numero da porta UDP
  *************************************************************** */
  public int getPortaUDP() {
    return portaUDP;
  }// fim do metodo getPortaUDP
}// fim da classe Usuario
