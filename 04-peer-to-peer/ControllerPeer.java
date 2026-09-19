/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/12/2025
* Ultima alteracao.: 05/12/2025
* Nome.............: ControllerPeer
* Funcao...........: Controlador da interface grafica do peer P2P.
*                    Gerencia grupos, envio e exibicao de mensagens.
*************************************************************** */

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import java.util.*;

public class ControllerPeer implements MensagemListener {

  // Componentes da interface
  @FXML private Label labelNomeUsuario;
  @FXML private TextField campoGrupo;
  @FXML private TextArea areaMensagens;
  @FXML private TextField campoMensagem;
  @FXML private VBox areaGrupos;
  @FXML private ListView<String> listaGrupos;
  @FXML private Button botaoCriarEntrar;
  @FXML private Button botaoSair;
  @FXML private Button botaoEnviar;

  // Instancia do peer P2P
  private Peer peer;

  // Armazena mensagens separadas por grupo
  private final Map<String, List<String>> mensagensPorGrupo = new HashMap<>();

  // Grupo selecionado na interface
  private String grupoAtual = null;

  /* ***************************************************************
  * Metodo: inicializarPeer
  * Funcao: associa um peer existente a interface grafica
  * Parametros: peer = instancia do peer P2P
  * Retorno: void
  *************************************************************** */
  public void inicializarPeer(Peer peer) {
    this.peer = peer;

    labelNomeUsuario.setText("Usuario: " + peer.getNome());
    peer.setListener(this);

    configurarEventos(); // registra eventos da interface
  } // fim do metodo inicializarPeer

  /* ***************************************************************
  * Metodo: onMensagemRecebida
  * Funcao: recebe mensagens UDP vindas do peer
  * Parametros: mensagem = conteudo recebido
  * Retorno: void
  *************************************************************** */
  @Override
  public void onMensagemRecebida(String mensagem) {
    Platform.runLater(() -> {

      String[] partes = mensagem.split("&", 3);
      if (partes.length != 3) return; // fim do if

      String grupo = partes[0];
      String usuario = partes[1];
      String conteudo = partes[2];

      // Ignora mensagens enviadas pelo proprio usuario
      if (usuario.equals(peer.getNome())) return; // fim do if

      // Ignora mensagens de grupos nos quais o usuario nao esta
      if (!mensagensPorGrupo.containsKey(grupo)) return; // fim do if

      mensagensPorGrupo.get(grupo).add("(" + usuario + "): " + conteudo);

      if (grupo.equals(grupoAtual)) {
        atualizarAreaMensagens();
      } // fim do if
    }); // fim do runLater
  } // fim do metodo onMensagemRecebida

  /* ***************************************************************
  * Metodo: criarOuEntrarGrupo
  * Funcao: realiza JOIN no grupo digitado pelo usuario
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  @FXML
  private void criarOuEntrarGrupo() {
    String grupo = campoGrupo.getText().trim();

    if (grupo.isEmpty() || grupo.contains("&")) {
      mostrarErro("Nome invalido");
      return; // fim do metodo
    } // fim do if

    peer.joinGroup(grupo);

    if (!mensagensPorGrupo.containsKey(grupo)) {
      mensagensPorGrupo.put(grupo, new ArrayList<>());
      listaGrupos.getItems().add(grupo);
    } // fim do if

    grupoAtual = grupo;
    listaGrupos.getSelectionModel().select(grupo);

    campoGrupo.clear();
  } // fim do metodo criarOuEntrarGrupo

  /* ***************************************************************
  * Metodo: sairGrupo
  * Funcao: realiza LEAVE do grupo selecionado
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  @FXML
  private void sairGrupo() {
    if (grupoAtual == null) return; // fim do if

    String grupoRemovido = grupoAtual;

    peer.leaveGroup(grupoRemovido);

    mensagensPorGrupo.remove(grupoRemovido);
    listaGrupos.getItems().remove(grupoRemovido);

    listaGrupos.getSelectionModel().clearSelection(); // evita selecao invalida

    // Escolhe automaticamente outro grupo caso exista
    if (!listaGrupos.getItems().isEmpty()) {

      String novoGrupo = listaGrupos.getItems().get(0);
      listaGrupos.getSelectionModel().select(novoGrupo);

      grupoAtual = novoGrupo;
      atualizarAreaMensagens();

    } else {
      grupoAtual = null;
      areaMensagens.clear();
    } // fim do else
  } // fim do metodo sairGrupo

  /* ***************************************************************
  * Metodo: enviarMensagem
  * Funcao: envia texto digitado para os membros do grupo atual
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  @FXML
  private void enviarMensagem() {
    String texto = campoMensagem.getText().trim();

    if (texto.isEmpty() || grupoAtual == null) return; // fim do if

    peer.enviarMensagem(grupoAtual, texto);

    mensagensPorGrupo.get(grupoAtual).add("(Voce): " + texto);

    atualizarAreaMensagens();
    campoMensagem.clear();
  } // fim do metodo enviarMensagem

  /* ***************************************************************
  * Metodo: configurarEventos
  * Funcao: registra eventos de botoes e listas da interface
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void configurarEventos() {

    botaoCriarEntrar.setOnAction(e -> criarOuEntrarGrupo());
    botaoSair.setOnAction(e -> sairGrupo());
    botaoEnviar.setOnAction(e -> enviarMensagem());

    campoMensagem.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ENTER) enviarMensagem();
    }); // fim do setOnKeyPressed

    listaGrupos.getSelectionModel().selectedItemProperty()
      .addListener((obs, antigo, novo) -> {

        // Evita selecao nula ou invalida
        if (novo == null) {
          grupoAtual = null;
          areaMensagens.clear();
          return; // fim do metodo
        } // fim do if

        if (!mensagensPorGrupo.containsKey(novo)) {
          listaGrupos.getSelectionModel().clearSelection();
          return; // fim do metodo
        } // fim do if

        grupoAtual = novo;
        atualizarAreaMensagens();
      }); // fim do listener
  } // fim do metodo configurarEventos

  /* ***************************************************************
  * Metodo: atualizarAreaMensagens
  * Funcao: exibe no TextArea todas as mensagens do grupo atual
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void atualizarAreaMensagens() {
    List<String> msgs = mensagensPorGrupo.getOrDefault(grupoAtual, Collections.emptyList());
    areaMensagens.setText(String.join("\n", msgs));
  } // fim do metodo atualizarAreaMensagens

  /* ***************************************************************
  * Metodo: encerrarAplicacao
  * Funcao: realiza encerramento seguro do peer e fecha a aplicacao
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  public void encerrarAplicacao() {
    System.out.println("[DEBUG] Encerrando aplicacao via ControllerPeer...");

    if (peer != null) {
      try {
        peer.encerrar(); // peer cuida do desligamento completo
      } catch (Exception e) {
        e.printStackTrace();
      } // fim do try-catch
    } // fim do if

    javafx.application.Platform.exit();
    System.exit(0);
  } // fim do metodo encerrarAplicacao

  /* ***************************************************************
  * Metodo: mostrarErro
  * Funcao: exibe uma caixa de dialogo com mensagem de erro
  * Parametros: msg = mensagem a ser exibida
  * Retorno: void
  *************************************************************** */
  private void mostrarErro(String msg) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setHeaderText(null);
    alert.setContentText(msg);
    alert.showAndWait();
  } // fim do metodo mostrarErro

} // fim da classe ControllerPeer
