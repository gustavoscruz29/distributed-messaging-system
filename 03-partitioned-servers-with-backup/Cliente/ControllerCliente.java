/* ***************************************************************
* Autor............: Gustavo dos Santos Cruz
* Matricula........: 202310272
* Inicio...........: 10/11/2025
* Ultima alteracao.: 15/11/2025
* Nome.............: ControllerCliente
* Funcao...........: Controlador da tela de chat
*************************************************************** */

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;

import java.util.HashMap;
import java.util.Map;

public class ControllerCliente {

  @FXML
  private ListView<String> listaGrupos;

  @FXML
  private TextArea areaMensagens;

  @FXML
  private TextField campoMensagem;

  @FXML
  private TextField campoGrupo;

  @FXML
  private Button botaoEnviar;

  @FXML
  private Button botaoCriarEntrar;

  @FXML
  private Button botaoSair;

  private Cliente cliente;

  // armazena os grupos de chat, onde a chave eh o nome do grupo e o valor eh o objeto Grupo
  private Map<String, Grupo> grupos = new HashMap<>();
  
  private String grupoAtual;

  /* ***************************************************************
   * Metodo: inicializarCliente
   * Funcao: inicializa o cliente e configura os eventos de recebimento de mensagens
   * Parametros: cliente = objeto cliente a ser inicializado
   * Retorno: void
   *************************************************************** */
  public void inicializarCliente(Cliente cliente) {
    this.cliente = cliente;

    cliente.iniciarRecebimento(new MensagemListener() {
      @Override
      public void onMensagemRecebida(String mensagem) {
        // chama o metodo para processar a mensagem recebida
        processarMensagemRecebida(mensagem);
      }//fim do metodo onMensagemRecebida
    });

    configurarEventos();
  }//fim do metodo inicializarCliente

  /* ***************************************************************
   * Metodo: configurarEventos
   * Funcao: configura os eventos dos botoes e campos da interface
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void configurarEventos() {
    botaoCriarEntrar.setOnAction(e -> criarOuEntrarGrupo());
    botaoEnviar.setOnAction(e -> enviarMensagem());

    // Enviar mensagem com ENTER
    campoMensagem.setOnKeyPressed(event -> {
      if (event.getCode() == KeyCode.ENTER) {
        event.consume(); // evita quebra de linha no TextField
        botaoEnviar.fire(); // aciona o evento do botaoEnviar
      }//fim do if
    });

    // configura o evento de selecao de um grupo na lista
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
   * Funcao: cria ou entra em um grupo
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void criarOuEntrarGrupo() {
    String nomeGrupo = campoGrupo.getText().trim(); // pega grupo e remove espacos

    if (nomeGrupo.isEmpty() || nomeGrupo.contains("&")) {
      Alert alert = new Alert(Alert.AlertType.WARNING);
      alert.setContentText("Entre com um nome de grupo válido (não vazio e sem &)");
      alert.showAndWait();
      return; // nao continua se nome do grupo vazio ou se nome do grupo contiver &
    }//fim do if

    char c = Character.toUpperCase(nomeGrupo.charAt(0));
    if (c < 'A' || c > 'Z') {
      Alert alert = new Alert(Alert.AlertType.WARNING);
      alert.setContentText("O nome do grupo deve começar com uma letra (A-Z)");
      alert.showAndWait();
      return; // nao continua se nome do grupo comecar sem letras do alfabeto
    }//fim do if

    new Thread(() -> {
      try {
        cliente.joinGrupo(nomeGrupo); // faz solicitacao de entrada em grupo via TCP

        Platform.runLater(() -> {
          if (!grupos.containsKey(nomeGrupo)) {
            Grupo grupo = new Grupo(nomeGrupo);
            grupos.put(nomeGrupo, grupo);
            listaGrupos.getItems().add(nomeGrupo);
          }//fim do if

          campoGrupo.clear();
          listaGrupos.getSelectionModel().select(nomeGrupo); // seleciona o grupo na lista de grupos
        });

      }//fim do try
      catch (Exception e) {
        Platform.runLater(() -> mostrarErro("Erro ao entrar/criar grupo: " + e.getMessage()));
      }//fim do catch
    }, "Join-Thread").start();
  }//fim do metodo criarOuEntrarGrupo

  /* ***************************************************************
   * Metodo: sairDoGrupo
   * Funcao: sai do grupo atual e atualiza a interface
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void sairDoGrupo() {
    if (grupoAtual == null)
      return; // nao continua

    new Thread(() -> {
      try {
        cliente.leaveGrupo(grupoAtual); // vai fazer a solicitacao ao servidor

        Platform.runLater(() -> {
          grupos.remove(grupoAtual);
          listaGrupos.getItems().remove(grupoAtual);
          grupoAtual = null; // zera o grupo
          listaGrupos.getSelectionModel().clearSelection(); // limpa a selecao da lista visual
          areaMensagens.clear();
        });

      }//fim do try
      catch (Exception e) {
        Platform.runLater(() -> mostrarErro("Erro ao sair do grupo: " + e.getMessage()));
      }//fim do catch
    }, "Leave-Thread").start();
  }//fim do metodo sairDoGrupo

  /* ***************************************************************
   * Metodo: enviarMensagem
   * Funcao: envia uma mensagem para o grupo atual e atualiza a interface
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void enviarMensagem() {
    String texto = campoMensagem.getText().trim(); // pega a mensagem e remove espacos
    if (texto.isEmpty() || grupoAtual == null)
      return; //nao continuar

    new Thread(() -> {
      try {
        cliente.enviarMensagem(grupoAtual, texto); // envia a mensagem ao grupo atual

        Platform.runLater(() -> {
          Grupo grupo = grupos.get(grupoAtual); // obtem o grupo
          if (grupo == null) {
            grupo = new Grupo(grupoAtual);
            grupos.put(grupoAtual, grupo);
            listaGrupos.getItems().add(grupoAtual);
          }//fim do if

          // adiciona mensagem ao grupo
          grupo.adicionarMensagem("(" + cliente.getNomeUsuario() + "): " + texto);
          atualizarAreaMensagens();
          campoMensagem.clear();
        });

      }//fim do try
      catch (Exception e) {
        Platform.runLater(() -> mostrarErro("Erro ao enviar mensagem: " + e.getMessage()));
      }//fim do catch
    }, "Send-Thread").start();
  }//fim do metodo enviarMensagem

  /* ***************************************************************
   * Metodo: processarMensagemRecebida
   * Funcao: processa uma mensagem recebida e a exibe no grupo correto
   * Parametros: mensagem = a mensagem recebida que sera processada
   * Retorno: nenhum
   *************************************************************** */
  private void processarMensagemRecebida(String mensagem) {
    if (!mensagem.startsWith("SEND"))
      return; // nao deve continuar se mensagem nao comecar com SEND

    // quebrar a mensagem em tres partes a partir do SEND sem os &
    String[] partes = mensagem.substring(5).split("&", 3);
    if (partes.length != 3)
      return; // se nao tiver tres partes nao deve continuar

    String usuario = partes[0].trim();
    String grupoNome = partes[1].trim();
    String conteudo = partes[2].trim();

    String linha = "(" + usuario + "): " + conteudo;

    Platform.runLater(() -> {
      Grupo grupo = grupos.get(grupoNome); // buscar por grupo
      // se o grupo nao existir, adicionar na lista de grupos
      if (grupo == null) {
        grupo = new Grupo(grupoNome);
        grupos.put(grupoNome, grupo);
        listaGrupos.getItems().add(grupoNome); // grupo adicionado na interface
      }//fim do if

      grupo.adicionarMensagem(linha); // adiciona mensagem ao grupo

      if (grupoNome.equals(grupoAtual)) {
        atualizarAreaMensagens();
      }//fim do if
    });
  }//fim do metodo processarMensagemRecebida

  /* ***************************************************************
   * Metodo: atualizarAreaMensagens
   * Funcao: atualiza a area de mensagens
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  private void atualizarAreaMensagens() {
    Grupo grupo = grupos.get(grupoAtual); // seleciona o grupo atual
    if (grupo != null) {
      // atualiza as mensagens a partir da lista de mensagens do grupo
      areaMensagens.setText(grupo.getMensagens().toString());
    }//fim do if
  }//fim do metodo atualizarAreaMensagens

  /* ***************************************************************
   * Metodo: mostrarErro
   * Funcao: exibe uma mensagem de erro
   * Parametros: texto = o texto que sera exibido no alerta
   * Retorno: void
  *************************************************************** */
  private void mostrarErro(String texto) {
    Alert alert = new Alert(Alert.AlertType.ERROR); // cria um alerta do tipo erro
    alert.setContentText(texto); // define o texto do alerta
    alert.setHeaderText(null); // remove o cabecalho do alerta
    alert.showAndWait(); // exibe o alerta e aguarda o usuario iteragir
  }//fim do metodo mostrarErro

  /* ***************************************************************
   * Metodo: encerrarAplicacao
   * Funcao: encerra a aplicacao, saindo de todos os grupos
   * Parametros: nenhum
   * Retorno: void
   *************************************************************** */
  public void encerrarAplicacao() {
    // verifica se o objeto cliente nao eh nulo
    if (cliente != null) {
      // itera sobre todos os grupos para sair de cada um
      for (String grupo : grupos.keySet()) {
        try {
          cliente.leaveGrupo(grupo); // tenta sair do grupo atual
        } //fim do catch
        catch (Exception e) {
          // ignora erros ao encerrar
        }//fim do try
      }//fim do for
      cliente.fechar();
    }//fim do if
  }//fim do metodo encerrarAplicacao
}//fim da classe ControllerCliente
