/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 04/07/2025
* Nome.............: MensagemListener
* Funcao...........: Interface para tratar mensagens recebidas via UDP
*************************************************************** */

/* ***************************************************************
  * Metodo: onMensagemRecebida
  * Funcao: Executado sempre que uma nova mensagem UDP for recebida
  * Parametros: mensagem = String contendo a mensagem recebida
  * Retorno: void
  *************************************************************** */
public interface MensagemListener {
  void onMensagemRecebida(String mensagem);
}// fim da interface MensagemListener
