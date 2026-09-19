/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 10/11/2025
* Ultima alteracao.: 15/11/2025
* Nome.............: Grupo
* Funcao...........: Representa um grupo de mensagens no cliente, 
                     contendo nome e historico
*************************************************************** */

public class Grupo {

  private String nome;
  private StringBuilder mensagens; // armazena o historico de mensagens do grupo

  /* ***************************************************************
   * Metodo: Grupo (construtor)
   * Funcao: Inicializa o grupo com um nome e historico vazio
   * Parametros: nome = nome do grupo
   * Retorno: void
   *************************************************************** */
  public Grupo(String nome) {
    this.nome = nome;
    this.mensagens = new StringBuilder();
  }// fim do construtor

  /* **************************************************************
   * Metodo: getNome
   * Funcao: Retorna o nome do grupo
   * Parametros: nenhum
   * Retorno: nome = String com o nome do grupo
   *************************************************************** */
  public String getNome() {
    return nome;
  }// fim do metodo getNome

  /* ***************************************************************
   * Metodo: getMensagens
   * Funcao: Retorna o historico de mensagens do grupo
   * Parametros: nenhum
   * Retorno: mensagens = StringBuilder contendo as mensagens
   *************************************************************** */
  public StringBuilder getMensagens() {
    return mensagens;
  }// fim do metodo getMensagens

  /* ***************************************************************
   * Metodo: adicionarMensagem
   * Funcao: Adiciona uma nova mensagem ao historico do grupo
   * Parametros: mensagem = texto da mensagem a ser adicionada
   * Retorno: void
   *************************************************************** */
  public void adicionarMensagem(String mensagem) {
    mensagens.append(mensagem).append("\n");
  }// fim do metodo adicionar mensagem

  /* ***************************************************************
   * Metodo: toString
   * Funcao: Retorna o nome do grupo para representacao textual
   * Parametros: nenhum
   * Retorno: nome = nome do grupo
   *************************************************************** */
  @Override
  public String toString() {
    return nome;
  }// fim do metodo toString

}// fim da classe Grupo
