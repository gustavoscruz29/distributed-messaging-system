/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 05/07/2025
* Nome.............: Principal
* Funcao...........: Classe principal que inicia a interface grafica do cliente
*************************************************************** */

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.event.EventHandler;
import javafx.stage.WindowEvent;

public class Principal extends Application {

  private ControllerCena1 controllerCena1 = new ControllerCena1(); // Controlador da cena 1
  private ControllerCliente controllerCliente; // Controlador da cena de chat

  /* ***************************************************************
  * Metodo: start
  * Funcao: Metodo inicial do JavaFX, carrega a primeira tela da interface
  * Parametros: stage = palco principal da aplicacao
  * Retorno: void
  *************************************************************** */
  @Override
  public void start(Stage stage) throws Exception {
    // carrega a cena inicial (cena1.fxml)
    FXMLLoader loader = new FXMLLoader(getClass().getResource("cena1.fxml"));
    Parent root = loader.load();

    controllerCena1 = loader.getController(); // obtem a referencia ao controlador da cena 1
    controllerCena1.setControllerPrincipal(this); // passa a referencia para o controlador, se necessario

    Scene scene = new Scene(root);
    stage.setScene(scene);
    stage.setTitle("Login - Cliente");

    // adiciona o listener para o fechamento da janela
    stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
      @Override
      public void handle(WindowEvent event) {
        if (controllerCliente != null) {
          controllerCliente.encerrarAplicacao(); // chama o metodo de encerramento do controllerCliente
        }//fim do if
      }//fim do handle
    });

    stage.show();
  }// fim do metodo start

  // metodo que sera chamado no ControllerCena1 para passar o controlador da proxima cena
  public void setControllerCliente(ControllerCliente controllerCliente) {
    this.controllerCliente = controllerCliente;
  }//fim do metodo setControllerCliente

  /* ***************************************************************
  * Metodo: main
  * Funcao: Metodo principal que inicia a aplicacao JavaFX
  * Parametros: args = argumentos de linha de comando
  * Retorno: void
  *************************************************************** */
  public static void main(String[] args) {
    launch(args);
  }// fim do metodo main
}// fim da classe Principal
