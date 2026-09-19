/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 05/07/2025
* Nome.............: ControllerCena1
* Funcao...........: Controlador da cena inicial. Recebe o nome do usuario,
*                    valida e inicia o Peer. Abre a cena principal do chat.
*************************************************************** */

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.Parent;

public class ControllerCena1 {

  @FXML
  private TextField campoNome;

  @FXML
  private Button botaoEntrar;

  private Principal principal;

  /* ***************************************************************
  * Metodo: initialize
  * Funcao: inicializa eventos da cena
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  @FXML
  public void initialize() {
    botaoEntrar.setOnAction(e -> entrar()); // clique inicia login
  } // fim do metodo initialize

  /* ***************************************************************
  * Metodo: setControllerPrincipal
  * Funcao: recebe referencia da classe Principal
  * Parametros: principal = instancia da classe Principal
  * Retorno: void
  *************************************************************** */
  public void setControllerPrincipal(Principal principal) {
    this.principal = principal;
  } // fim do metodo setControllerPrincipal

  /* ***************************************************************
  * Metodo: entrar
  * Funcao: valida o nome e inicia um Peer em nova thread.
  *         Depois carrega a cena do chat.
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void entrar() {

    String nome = campoNome.getText().trim();

    if (nome.isEmpty() || nome.contains("&")) {
      mostrarAlerta("Nome (sem &) eh obrigatorio.");
      return; // fim do metodo
    } // fim do if

    // Thread separada para nao travar a interface
    new Thread(() -> {
      try {

        Peer peer = new Peer(nome); // cria peer apenas uma vez

        Platform.runLater(() -> {
          try {

            FXMLLoader loader = new FXMLLoader(getClass().getResource("cena2.fxml"));
            Parent root = loader.load();

            ControllerPeer controller = loader.getController();
            controller.inicializarPeer(peer); // passa peer ja configurado
            principal.setControllerPeer(controller);

            Stage stage = (Stage) campoNome.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.setTitle("Chat P2P - " + nome);

          } catch (Exception e) {
            e.printStackTrace();
            mostrarAlerta("Erro ao carregar interface do Peer.");
          } // fim do try-catch interno
        }); // fim do runLater

      } catch (Exception ex) {
        ex.printStackTrace();
        Platform.runLater(() -> mostrarAlerta("Erro ao iniciar Peer."));
      } // fim do try-catch externo
    }).start(); // fim da thread
  } // fim do metodo entrar

  /* ***************************************************************
  * Metodo: mostrarAlerta
  * Funcao: exibe mensagem de erro ao usuario
  * Parametros: mensagem - texto do alerta
  * Retorno: void
  *************************************************************** */
  private void mostrarAlerta(String mensagem) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setHeaderText(null);
    alert.setContentText(mensagem);
    alert.showAndWait();
  } // fim do metodo mostrarAlerta

} // fim da classe ControllerCena1
