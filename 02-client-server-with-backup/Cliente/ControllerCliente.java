/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 01/07/2025
* Ultima alteracao.: 03/07/2025
* Nome.............: ControllerCliente
* Funcao...........: Controlador da tela de chat
*************************************************************** */

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.util.HashMap;
import java.util.Map;

public class ControllerCliente {

  @FXML
  private ListView<String> listaGrupos; // Lista de grupos disponiveis

  @FXML
  private TextArea areaMensagens; // Area para exibir as mensagens do grupo atual

  @FXML
  private TextField campoMensagem; // Campo para digitar a nova mensagem

  @FXML
  private TextField campoGrupo; // Campo para criar ou entrar em grupo

  @FXML
  private Button botaoEnviar;

  @FXML
  private Button botaoCriarEntrar;

  @FXML
  private Button botaoSair;

  private Cliente cliente;
  private Map<String, Grupo> grupos = new HashMap<>(); // Armazena os grupos e suas mensagens
  private String grupoAtual; // Grupo selecionado atualmente na interface

  /* ***************************************************************
  * Metodo: inicializarCliente
  * Funcao: Associa o cliente ao controlador e inicia escuta de mensagens
  * Parametros: cliente = instancia do cliente conectada ao servidor
  * Retorno: void
  *************************************************************** */
  public void inicializarCliente(Cliente cliente) {
    this.cliente = cliente;

    cliente.iniciarRecebimento(new MensagemListener() {
      @Override
      public void onMensagemRecebida(String mensagem) {
        processarMensagemRecebida(mensagem);
      }//fim do metodo onMensagemRecebida
    });

    configurarEventos();
  }//fim do metodo inicializarCliente

  /* ***************************************************************
  * Metodo: configurarEventos
  * Funcao: Define os comportamentos dos botoes e da lista de grupos
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void configurarEventos() {
    botaoCriarEntrar.setOnAction(e -> criarOuEntrarGrupo());
    botaoEnviar.setOnAction(e -> enviarMensagem());
    listaGrupos.getSelectionModel().selectedItemProperty().addListener((obs, antigo, novo) -> {
      if (novo != null) {
        grupoAtual = novo;
        atualizarAreaMensagens();
      }//fim do if
    });
    botaoSair.setOnAction(e -> sairDoGrupo());
  }//fim do metodo configurarEventos

  /* ***************************************************************
  * Metodo: criarOuEntrarGrupo
  * Funcao: Realiza requisicao JOIN ao servidor e atualiza interface
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void criarOuEntrarGrupo() {
    String nomeGrupo = campoGrupo.getText().trim();
    if (nomeGrupo.isEmpty() || nomeGrupo.contains("&")){
      Alert alert = new Alert(Alert.AlertType.WARNING);
      alert.setContentText("Entrar com nome sem & ou vazio");
      alert.showAndWait();
      return;
    }//fim do if

    try {
      cliente.joinGrupo(nomeGrupo); // so envia, não retorna nada

      if (!grupos.containsKey(nomeGrupo)) {
        Grupo grupo = new Grupo(nomeGrupo);
        grupos.put(nomeGrupo, grupo);
        listaGrupos.getItems().add(nomeGrupo);
      }//fim do if

      campoGrupo.clear();
      listaGrupos.getSelectionModel().select(nomeGrupo);

    } catch (Exception e) {
      e.printStackTrace();
      
      mostrarErro("Erro ao entrar/criar grupo.");
    }//fim do try-catch
  }//fim do metodo criarOuEntrarGrupo


  /* ***************************************************************
  * Metodo: sairDoGrupo
  * Funcao: Realiza requisicao LEAVE ao servidor e atualiza interface
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void sairDoGrupo() {
    if (grupoAtual == null)
      return;

    try {
      cliente.leaveGrupo(grupoAtual); // só envia, não retorna nada

      grupos.remove(grupoAtual);
      listaGrupos.getItems().remove(grupoAtual);
      grupoAtual = null;
      listaGrupos.getSelectionModel().clearSelection();
      areaMensagens.clear();

    } catch (Exception e) {
      e.printStackTrace();
      mostrarErro("Erro ao sair do grupo.");
    }//fim do try-catch
  }//fim do metodo sairDoGrupo


  /* ***************************************************************
  * Metodo: enviarMensagem
  * Funcao: Envia mensagem para o grupo atual e atualiza interface local
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void enviarMensagem() {
    String texto = campoMensagem.getText().trim();
    if (texto.isEmpty() || grupoAtual == null)
      return;

    try {
      cliente.enviarMensagem(grupoAtual, texto);

      Grupo grupo = grupos.get(grupoAtual);
      grupo.adicionarMensagem("(" + cliente.getNomeUsuario() + "): " + texto);

      atualizarAreaMensagens();
      campoMensagem.clear();

    } catch (Exception e) {
      e.printStackTrace();
      mostrarErro("Erro ao enviar mensagem.");
    }//fim do try-catch
  }//fim do metodo enviarMensagem

  /* ***************************************************************
  * Metodo: processarMensagemRecebida
  * Funcao: Trata mensagens UDP recebidas e atualiza historico e interface
  * Parametros: mensagem = conteudo da mensagem recebida
  * Retorno: void
  *************************************************************** */
  private void processarMensagemRecebida(String mensagem) {
    if (!mensagem.startsWith("SEND"))
      return;

    String[] partes = mensagem.substring(5).split("&", 3);
    if (partes.length != 3)
      return;

    String grupoNome = partes[1].trim();
    String usuario = partes[0].trim();
    String conteudo = partes[2].trim();

    String linha = "(" + usuario + "): " + conteudo;

    Platform.runLater(() -> { // Garante atualizacao da GUI na thread correta
      Grupo grupo = grupos.get(grupoNome);
      if (grupo == null) {
        grupo = new Grupo(grupoNome);
        grupos.put(grupoNome, grupo);
        listaGrupos.getItems().add(grupoNome);
      }//fim do if

      grupo.adicionarMensagem(linha);

      if (grupoNome.equals(grupoAtual)) {
        atualizarAreaMensagens();
      }//fim do if
    });
  }//fim do metodo processarMensagemRecebida

  /* ***************************************************************
  * Metodo: atualizarAreaMensagens
  * Funcao: Atualiza a TextArea com as mensagens do grupo atual
  * Parametros: nenhum
  * Retorno: void
  *************************************************************** */
  private void atualizarAreaMensagens() {
    Grupo grupo = grupos.get(grupoAtual);
    if (grupo != null) {
      areaMensagens.setText(grupo.getMensagens().toString());
    }//fim do if
  }//fim do metodo atualizarAreaMensagens

  /* ***************************************************************
  * Metodo: mostrarErro
  * Funcao: Exibe uma mensagem de erro para o usuario
  * Parametros: texto = mensagem a ser exibida
  * Retorno: void
  *************************************************************** */
  private void mostrarErro(String texto) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setContentText(texto);
    alert.setHeaderText(null);
    alert.showAndWait();
  }//fim do metodo mostrarErro
  
  public void encerrarAplicacao() {
    if (cliente != null) {
      for (String grupo : grupos.keySet()) {
        cliente.leaveGrupo(grupo);
      }//fim do for
    }//fim do if
  }//fim do metodo encerrarAplicacao
  
}//fim da classe ControllerCliente
