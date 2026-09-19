/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 05/12/2025
* Nome.............: Principal
* Funcao...........: Classe principal da aplicacao. Responsavel por
*                    iniciar a interface grafica e carregar a primeira cena.
*************************************************************** */

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.event.EventHandler;
import javafx.stage.WindowEvent;

public class Principal extends Application {

  private ControllerCena1 controllerCena1 = new ControllerCena1(); 
  private ControllerPeer controllerPeer;

  @Override
  public void start(Stage stage) throws Exception {
    // Carrega o arquivo FXML que contem a primeira tela
    FXMLLoader loader = new FXMLLoader(getClass().getResource("cena1.fxml"));
    Parent root = loader.load();

    // Obtem o controlador da cena inicial
    controllerCena1 = loader.getController();
    controllerCena1.setControllerPrincipal(this);

    Scene scene = new Scene(root);
    stage.setScene(scene);
    stage.setTitle("Login - Peer P2P");

    // Tratamento de fechamento seguro da aplicacao
    stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
      @Override
      public void handle(WindowEvent event) {
        if (controllerPeer != null) { // verifica se o controlador foi iniciado
          controllerPeer.encerrarAplicacao(); 
        } // fim do if
      } // fim do metodo handle
    }); // fim do stage.setOnCloseRequest

    stage.show();
  } // fim do metodo start

  /* ***************************************************************
   * Metodo: setControllerPeer
   * Funcao: Recebe o controlador da tela principal de chat quando ela
   *         eh carregada pela ControllerCena1.
   * Parametros: controllerPeer = referencia para o controlador do chat
   * Retorno: void
   *************************************************************** */
  public void setControllerPeer(ControllerPeer controllerPeer) {
    this.controllerPeer = controllerPeer;
  } // fim do metodo setControllerPeer

  public static void main(String[] args) {
    launch(args);
  } // fim do metodo main

} // fim da classe Principal
