/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 04/07/2025
* Nome.............: Principal
* Funcao...........: Classe principal para iniciar o servidor 
*************************************************************** */

public class Principal {

  /* ***************************************************************
  * Metodo: main
  * Funcao: Instancia o servidor e chama o metodo para inicializa-lo
  * Parametros: args = argumentos de linha de comando
  * Retorno: void
  *************************************************************** */
  public static void main(String[] args) {
    Servidor servidor = new Servidor(); // Cria uma instancia do servidor
    servidor.iniciar(); // Inicia o servidor (abre portas TCP/UDP)
  }// fim do metodo main
}// fim da classe Principal
