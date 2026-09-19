/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 10/11/2025
* Ultima alteracao.: 15/11/2025
* Nome.............: ControllerCena1
* Funcao...........: Controlador da cena inicial
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
  private TextField campoIP; 
  @FXML
  private Button botaoEntrar;

  private Principal principal;

  @FXML
  public void initialize() {
    botaoEntrar.setOnAction(e -> entrar());
  }//fim do metodo initialize

  public void setControllerPrincipal(Principal principal) {
    this.principal = principal;
  }//fim do metodo setControllerPrincipal

  /* ***************************************************************
   * Metodo: entrar
   * Funcao: inicia o cliente com o nome fornecido e carrega a interface do chat
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void entrar() {
    String nome = campoNome.getText().trim();

    // verifica se o nome esta vazio ou contem o caractere "&"
    if (nome.isEmpty() || nome.contains("&")) {
      mostrarAlerta("Nome (sem &) é obrigatório.");
      return;
    }//fim do if


    new Thread(() -> {
      try {
        Cliente cliente = new Cliente(nome); 

        Platform.runLater(() -> {
          try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("cena2.fxml"));
            Parent root = loader.load();

            // obtem o controller da nova cena e inicializa o cliente
            ControllerCliente controller = loader.getController();
            controller.inicializarCliente(cliente);
            principal.setControllerCliente(controller);

            // altera a cena para a interface do chat e atualiza o titulo da janela
            Stage stage = (Stage) campoNome.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.setTitle("Chat - " + nome);
          }//fim do try
          catch (Exception e) {
            // se houver erro ao carregar a interface, exibe um alerta
            e.printStackTrace();
            mostrarAlerta("Erro ao carregar a interface do chat.");
          }//fim do catch
        });

      }//fim do try
      catch (Exception ex) {
        // se houver erro ao iniciar o cliente, exibe um alerta
        ex.printStackTrace();
        Platform.runLater(() -> mostrarAlerta("Erro ao iniciar cliente."));
      }//fim do catch
    }, "UI-Entrar-Thread").start();
  }//fim do metodo entrar

  /* ***************************************************************
   * Metodo: mostrarAlerta
   * Funcao: exibe um alerta de erro com a mensagem fornecida
   * Parametros: mensagem - a mensagem a ser exibida no alerta
   * Retorno: void
   *************************************************************** */
  private void mostrarAlerta(String mensagem) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setHeaderText(null);
    alert.setContentText(mensagem);
    alert.showAndWait();
  }//fim do metodo mostrarAlerta

}//fim da classe ControllerCena1
