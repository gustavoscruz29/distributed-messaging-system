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
  private TextField campoNome; // Campo de texto para o nome do usuario

  @FXML
  private TextField campoIP; // Campo de texto para o IP do servidor

  @FXML
  private Button botaoEntrar; // Botao para entrar no sistema

  private Principal principal; // Referência ao controlador da classe principal

  /*
   * ***************************************************************
   * Metodo: initialize
   * Funcao: Inicializa os eventos da tela (associa o botao ao metodo entrar)
   * Parametros: nenhum
   * Retorno: void
   */
  @FXML
  public void initialize() {
    botaoEntrar.setOnAction(e -> entrar());
  }// fim do metodo initialize

  // Método para passar a referência do controlador Principal
  public void setControllerPrincipal(Principal principal) {
    this.principal = principal;
  }

  /*
   * ***************************************************************
   * Metodo: entrar
   * Funcao: Cria o cliente com nome e IP, carrega a cena principal do chat
   * Parametros: nenhum (usa os campos da interface)
   * Retorno: void
   */
  private void entrar() {
    String nome = campoNome.getText().trim();

    if (nome.isEmpty() || nome.contains("&")) {
      mostrarAlerta("Nome (sem &) é obrigatório.");
      return;
    }

    new Thread(() -> {
      try {
        Cliente cliente = new Cliente(nome); // fica esperando até achar servidor

        Platform.runLater(() -> {
          try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("cena2.fxml"));
            Parent root = loader.load();

            ControllerCliente controller = loader.getController();
            controller.inicializarCliente(cliente);
            principal.setControllerCliente(controller);

            Stage stage = (Stage) campoNome.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.setTitle("Chat - " + nome);
          } catch (Exception e) {
            e.printStackTrace();
            mostrarAlerta("Erro ao carregar a interface do chat.");
          }
        });

      } catch (Exception ex) {
        ex.printStackTrace();
        Platform.runLater(() -> mostrarAlerta("Erro na descoberta do servidor."));
      }
    }).start();
  }

  /*
   * ***************************************************************
   * Metodo: mostrarAlerta
   * Funcao: Exibe uma caixa de alerta com mensagem de erro
   * Parametros: mensagem = texto da mensagem a ser exibida
   * Retorno: void
   */
  private void mostrarAlerta(String mensagem) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setHeaderText(null);
    alert.setContentText(mensagem);
    alert.showAndWait();
  }// fim do metodo mostrarAlerta
}// fim da classe ControllerCena1
