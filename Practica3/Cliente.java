import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Cliente extends Application {

    // ===== Configuración =====
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5555; // Debe coincidir con Servidor.java
    private static final int CHAT_PORT = 6000;
    private static final int MAX_PACKET_SIZE = 65507;

    // Audio UDP (fragmentación)
    private static final int AUDIO_CHUNK_RAW_BYTES = 900;
    private static final long ASSEMBLY_TIMEOUT_MS = 60_000;

    // Sticker UDP (imagen)
    private static final int STICKER_CHUNK_RAW_BYTES = 700;

    // Emojis seguros (unicode escapes)
    private static final String E_SONRISA = "\uD83D\uDE04";
    private static final String E_MIC     = "\uD83C\uDFA4";
    private static final String E_AUDIO   = "\uD83C\uDFA7";

    // Salas multicast
    private static final Map<String, String> SALAS_MULTICAST;
    static {
        Map<String, String> tmp = new LinkedHashMap<>();
        tmp.put("sala1", "230.0.0.1");
        tmp.put("sala2", "230.0.0.2");
        tmp.put("sala3", "230.0.0.3");
        SALAS_MULTICAST = Collections.unmodifiableMap(tmp);
    }

    // ===== Estado =====
    private String username;
    private InetAddress serverAddress;
    private DatagramSocket serverSocket;
    private MulticastSocket chatSocket;
    private NetworkInterface netIf;

    private final Set<String> salasUnidas = ConcurrentHashMap.newKeySet();

    // ===== UI =====
    private Font uiFont;
    private ComboBox<String> comboSalas;
    private TabPane tabs;

    private TextField inputField;
    private CheckBox chkPrivado;
    private Button btnEmojis, btnSticker, btnEnviar;

    private Button btnGrabar, btnDetener, btnEnviarAudio;
    private Label lblEstadoGrab;

    // sala -> modelos UI
    private final Map<String, ObservableList<ChatMessage>> mensajesPorSala = new ConcurrentHashMap<>();
    private final Map<String, ListView<ChatMessage>> chatViews = new ConcurrentHashMap<>();
    private final Map<String, ObservableList<String>> usuariosPorSalaUI = new ConcurrentHashMap<>();
    private final Map<String, ListView<String>> userViews = new ConcurrentHashMap<>();

    // ===== Mensajes =====
    private static class ChatMessage {
        final String sala, from, to, tipo, texto, hora;
        final boolean privado;
        final byte[] data; // AUDIO o STICKER

        ChatMessage(String sala, String from, boolean privado, String to, String tipo, String texto, byte[] data) {
            this.sala = sala;
            this.from = from;
            this.privado = privado;
            this.to = to;
            this.tipo = tipo;
            this.texto = texto;
            this.data = data;
            this.hora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
    }

    // ===== Grabación =====
    private volatile boolean grabando = false;
    private TargetDataLine micLine;
    private Thread hiloGrabacion;
    private ByteArrayOutputStream pcmBuffer;
    private byte[] ultimoAudioWav;

    // ===== Reensamblado de paquetes (audio/sticker) =====
    private static class IncomingAssembly {
        final String sala, from, to, kind; // "AUDIO" o "STICKER"
        final boolean privado;
        final int total;
        final byte[][] parts;
        final BitSet received = new BitSet();
        volatile long lastUpdate = System.currentTimeMillis();
        final AtomicBoolean finalized = new AtomicBoolean(false);

        IncomingAssembly(String sala, String from, boolean privado, String to, String kind, int total) {
            this.sala = sala;
            this.from = from;
            this.privado = privado;
            this.to = to;
            this.kind = kind;
            this.total = total;
            this.parts = new byte[total][];
        }

        void put(int idx, byte[] data) {
            if (idx < 0 || idx >= total) return;
            if (parts[idx] == null) {
                parts[idx] = data;
                received.set(idx);
            }
            lastUpdate = System.currentTimeMillis();
        }

        boolean complete() {
            return received.cardinality() == total;
        }

        byte[] assemble() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                if (parts[i] == null) throw new IOException("Faltan partes");
                out.write(parts[i]);
            }
            return out.toByteArray();
        }
    }

    // id -> assembly
    private final Map<String, IncomingAssembly> incoming = new ConcurrentHashMap<>();

        @Override
    public void start(Stage stage) {
        username = pedirUsuario();
        if (username == null) {
            Platform.exit();
            return;
        }

        uiFont = Font.font("Segoe UI Emoji", 14);

        try {
            initNetworking();
        } catch (Exception e) {
            alertError("Error inicializando red: " + e.getMessage());
            Platform.exit();
            return;
        }

        BorderPane root = buildUI();

        Scene scene = new Scene(root, 980, 620);
        stage.setTitle("Chat JavaFX - Usuario: " + username + " " + E_SONRISA);
        stage.setScene(scene);
        stage.show();

        startListeners();
        startCleanupThread();

        stage.setOnCloseRequest(e -> {
            e.consume();
            closeClient();
            Platform.exit();
        });

        info("Listo.\n1) Unete a una sala.\n2) Texto y privados.\n3) Audio: Grabar -> Detener -> Enviar.\n4) Sticker: elegir imagen.");
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();

        // TOP
        HBox top = new HBox(10);
        top.setPadding(new Insets(10));

        comboSalas = new ComboBox<>(FXCollections.observableArrayList(SALAS_MULTICAST.keySet()));
        comboSalas.getSelectionModel().selectFirst();

        Button btnUnir = new Button("Unir");
        Button btnSalir = new Button("Salir");
        Button btnMis = new Button("Mis salas");

        btnUnir.setOnAction(e -> unirSala());
        btnSalir.setOnAction(e -> salirSala());
        btnMis.setOnAction(e -> info("Salas: " + salasUnidas));

        top.getChildren().addAll(new Label("Sala:"), comboSalas, btnUnir, btnSalir, btnMis);
        root.setTop(top);

        // CENTER
        tabs = new TabPane();
        root.setCenter(tabs);

        // BOTTOM (texto)
        inputField = new TextField();
        inputField.setFont(uiFont);
        inputField.setPromptText("Mensaje... )");

        chkPrivado = new CheckBox("Privado");

        btnEmojis = new Button("Emojis");
        btnEmojis.setOnAction(e -> showEmojiMenu(btnEmojis));

        btnSticker = new Button("Sticker");
        btnSticker.setOnAction(e -> enviarSticker());

        btnEnviar = new Button("Enviar");
        btnEnviar.setOnAction(e -> enviarTexto());
        inputField.setOnAction(e -> enviarTexto());

        HBox bottom1 = new HBox(10, inputField, chkPrivado, btnEmojis, btnSticker, btnEnviar);
        bottom1.setPadding(new Insets(10));
        HBox.setHgrow(inputField, Priority.ALWAYS);

        // BOTTOM (audio)
        btnGrabar = new Button(E_MIC + " Grabar");
        btnDetener = new Button("Detener");
        btnEnviarAudio = new Button(E_AUDIO + " Enviar audio");
        lblEstadoGrab = new Label(" ");

        btnDetener.setDisable(true);
        btnEnviarAudio.setDisable(true);

        btnGrabar.setOnAction(e -> grabar());
        btnDetener.setOnAction(e -> detener());
        btnEnviarAudio.setOnAction(e -> enviarAudio());

        HBox bottom2 = new HBox(10, btnGrabar, btnDetener, btnEnviarAudio, lblEstadoGrab);
        bottom2.setPadding(new Insets(0, 10, 10, 10));

        root.setBottom(new VBox(bottom1, bottom2));

        return root;
    }

    private void ensureTab(String sala) {
        if (chatViews.containsKey(sala)) return;

        ObservableList<ChatMessage> mensajes = FXCollections.observableArrayList();
        mensajesPorSala.put(sala, mensajes);

        ListView<ChatMessage> chatList = new ListView<>(mensajes);
        chatList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessage m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String marca = m.privado ? "[PRIVADO] " : "";
                String head = "[" + m.hora + "] " + marca + m.from + ": ";

                if ("TEXT".equals(m.tipo)) {
                    Label lbl = new Label(head + m.texto);
                    lbl.setWrapText(true);
                    lbl.setFont(uiFont);
                    setGraphic(lbl);
                    setText(null);
                } else if ("AUDIO".equals(m.tipo)) {
                    Label lbl = new Label(head + "(audio)");
                    lbl.setFont(uiFont);
                    Button play = new Button("▶ Reproducir");
                    play.setOnAction(ev -> playWavBytes(m.data));
                    HBox row = new HBox(10, lbl, play);
                    row.setPadding(new Insets(4, 0, 4, 0));
                    setGraphic(row);
                    setText(null);
                } else if ("STICKER".equals(m.tipo)) {
                    Label lbl = new Label(head);
                    lbl.setFont(uiFont);

                    Image img = new Image(new ByteArrayInputStream(m.data));
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(140);
                    iv.setPreserveRatio(true);

                    VBox box = new VBox(6, lbl, iv);
                    box.setPadding(new Insets(4, 0, 4, 0));
                    setGraphic(box);
                    setText(null);
                } else {
                    Label lbl = new Label(head + "(tipo " + m.tipo + ")");
                    lbl.setFont(uiFont);
                    setGraphic(lbl);
                    setText(null);
                }
            }
        });
        chatViews.put(sala, chatList);

        ObservableList<String> users = FXCollections.observableArrayList();
        usuariosPorSalaUI.put(sala, users);

        ListView<String> usersView = new ListView<>(users);
        usersView.setPrefWidth(220);
        userViews.put(sala, usersView);

        VBox right = new VBox(6, new Label("Usuarios"), usersView);
        right.setPadding(new Insets(6));

        SplitPane split = new SplitPane(chatList, right);
        split.setDividerPositions(0.75);

        Tab tab = new Tab(sala, split);
        tab.setClosable(false);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private String salaActual() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : tab.getText();
    }

    private void showEmojiMenu(Button anchor) {
        ContextMenu menu = new ContextMenu();
        String[] emojis = {
                "\uD83D\uDE04", "\uD83D\uDE02", "\uD83D\uDD25", "\u2764\uFE0F", "\uD83D\uDC4D",
                "\uD83D\uDE0E", "\uD83C\uDF89"
        };
        for (String e : emojis) {
            MenuItem it = new MenuItem(e);
            it.setOnAction(ev -> inputField.appendText(e));
            menu.getItems().add(it);
        }
        menu.show(anchor, Side.TOP, 0, 0);
    }

        private void initNetworking() throws Exception {
        serverAddress = InetAddress.getByName(SERVER_HOST);
        serverSocket = new DatagramSocket(); // puerto aleatorio
        chatSocket = new MulticastSocket(CHAT_PORT);
        chatSocket.setReuseAddress(true);

        netIf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
    }

    private void startListeners() {
        Thread t1 = new Thread(this::listenUserLists, "UserListListener");
        t1.setDaemon(true);
        t1.start();

        Thread t2 = new Thread(this::listenChat, "ChatListener");
        t2.setDaemon(true);
        t2.start();
    }

    private void listenUserLists() {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        while (!serverSocket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                serverSocket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                handleUserList(msg);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) System.err.println("USERLIST error: " + e.getMessage());
            }
        }
    }

    private void handleUserList(String msg) {
        // USERLIST|sala|u1,u2,...
        String[] parts = msg.split("\\|", 3);
        if (parts.length < 3) return;
        if (!"USERLIST".equals(parts[0])) return;

        String sala = parts[1];
        String lista = parts[2].trim();

        Set<String> usuarios = new LinkedHashSet<>();
        if (!lista.isEmpty()) {
            for (String u : lista.split(",")) {
                String uu = u.trim();
                if (!uu.isEmpty()) usuarios.add(uu);
            }
        }

        Platform.runLater(() -> {
            ensureTab(sala);
            ObservableList<String> model = usuariosPorSalaUI.get(sala);
            if (model != null) model.setAll(usuarios);
            addSystemMessage(sala, "Usuarios activos: " + usuarios);
        });
    }

    private void unirSala() {
        String sala = comboSalas.getSelectionModel().getSelectedItem();
        if (sala == null) return;
        if (salasUnidas.contains(sala)) { info("Ya estas en " + sala); return; }

        try {
            InetAddress group = InetAddress.getByName(SALAS_MULTICAST.get(sala));
            chatSocket.joinGroup(new InetSocketAddress(group, CHAT_PORT), netIf);

            salasUnidas.add(sala);
            Platform.runLater(() -> {
                ensureTab(sala);
                addSystemMessage(sala, "Te uniste a " + sala);
            });

            sendToServer("JOIN|" + sala + "|" + username);
        } catch (Exception e) {
            alertError("No se pudo unir: " + e.getMessage());
        }
    }

    private void salirSala() {
        String sala = salaActual();
        if (sala == null) { info("No hay sala."); return; }
        if (!salasUnidas.contains(sala)) return;

        try {
            InetAddress group = InetAddress.getByName(SALAS_MULTICAST.get(sala));
            chatSocket.leaveGroup(new InetSocketAddress(group, CHAT_PORT), netIf);

            salasUnidas.remove(sala);
            addSystemMessage(sala, "Saliste de " + sala);

            sendToServer("LEAVE|" + sala + "|" + username);
        } catch (Exception e) {
            alertError("No se pudo salir: " + e.getMessage());
        }
    }

    private void sendToServer(String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        DatagramPacket p = new DatagramPacket(data, data.length, serverAddress, SERVER_PORT);
        serverSocket.send(p);
    }

        private void listenChat() {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        while (!chatSocket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                chatSocket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                handleChat(msg);
            } catch (IOException e) {
                if (!chatSocket.isClosed()) System.err.println("CHAT error: " + e.getMessage());
            }
        }
    }

    private void handleChat(String msg) {
        // CHAT|sala|from|tipo|to|payload
        String[] parts = msg.split("\\|", 6);
        if (parts.length < 6) return;
        if (!"CHAT".equals(parts[0])) return;

        String sala = parts[1];
        String from = parts[2];
        String tipo = parts[3];
        String to = parts[4];
        String payload = parts[5];

        if (!salasUnidas.contains(sala)) return;

        boolean privado = !"*".equals(to);
        if (privado && !(to.equals(username) || from.equals(username))) return;

        switch (tipo) {
            case "TEXT":
                Platform.runLater(() -> addMessage(sala, new ChatMessage(sala, from, privado, to, "TEXT", payload, null)));
                break;

            case "AUDIO_START":
            case "AUDIO_CHUNK":
            case "AUDIO_END":
                handleAssembly(tipo, "AUDIO", sala, from, privado, to, payload);
                break;

            case "STICKER_START":
            case "STICKER_CHUNK":
            case "STICKER_END":
                handleAssembly(tipo, "STICKER", sala, from, privado, to, payload);
                break;

            default:
                Platform.runLater(() -> addSystemMessage(sala, "Tipo no soportado: " + tipo));
        }
    }

    private void enviarTexto() {
        String sala = salaActual();
        if (sala == null || !salasUnidas.contains(sala)) { info("Unete a una sala."); return; }

        String txt = inputField.getText().trim();
        if (txt.isEmpty()) return;

        String to = "*";
        if (chkPrivado.isSelected()) {
            ListView<String> lv = userViews.get(sala);
            String sel = (lv == null) ? null : lv.getSelectionModel().getSelectedItem();
            if (sel == null || sel.equals(username)) {
                info("Selecciona un usuario (no tu) para privado.");
                return;
            }
            to = sel;
        }

        sendChatMessage(sala, "TEXT", to, txt);
        inputField.clear();
    }

    private void sendChatMessage(String sala, String tipo, String to, String payload) {
        try {
            String safePayload = payload.replace("|", "/");
            String msg = "CHAT|" + sala + "|" + username + "|" + tipo + "|" + to + "|" + safePayload;

            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(SALAS_MULTICAST.get(sala));
            chatSocket.send(new DatagramPacket(data, data.length, group, CHAT_PORT));
        } catch (Exception e) {
            Platform.runLater(() -> alertError("Error enviando: " + e.getMessage()));
        }
    }


        private void handleAssembly(String tipoMsg, String kind,
                                String sala, String from, boolean privado, String to, String payload) {
        try {
            if (tipoMsg.endsWith("_START")) {
                // payload: id,total
                String[] p = payload.split(",", 2);
                String id = p[0].trim();
                int total = Integer.parseInt(p[1].trim());

                incoming.put(id, new IncomingAssembly(sala, from, privado, to, kind, total));
                Platform.runLater(() -> addSystemMessage(sala, "Recibiendo " + kind + " de " + from + " (" + total + " partes)..."));
                return;
            }

            if (tipoMsg.endsWith("_CHUNK")) {
                // payload: id,idx,b64
                String[] p = payload.split(",", 3);
                String id = p[0].trim();
                int idx = Integer.parseInt(p[1].trim());
                byte[] data = Base64.getDecoder().decode(p[2]);

                IncomingAssembly as = incoming.get(id);
                if (as != null) {
                    as.put(idx, data);
                    if (as.complete()) finalizeAssembly(id, as);
                }
                return;
            }

            if (tipoMsg.endsWith("_END")) {
                String id = payload.trim();
                IncomingAssembly as = incoming.get(id);
                if (as != null) finalizeAssembly(id, as);
            }

        } catch (Exception e) {
            Platform.runLater(() -> addSystemMessage(sala, "Error ensamblando " + kind + ": " + e.getMessage()));
        }
    }

    private void finalizeAssembly(String id, IncomingAssembly as) {
        if (!as.finalized.compareAndSet(false, true)) return;
        incoming.remove(id);

        if (!as.complete()) {
            Platform.runLater(() -> addSystemMessage(as.sala, as.kind + " incompleto (UDP)"));
            return;
        }

        try {
            byte[] full = as.assemble();
            String tipo = as.kind.equals("AUDIO") ? "AUDIO" : "STICKER";

            ChatMessage m = new ChatMessage(as.sala, as.from, as.privado, as.to, tipo, null, full);
            Platform.runLater(() -> addMessage(as.sala, m));
        } catch (Exception e) {
            Platform.runLater(() -> addSystemMessage(as.sala, "No se pudo reconstruir " + as.kind));
        }
    }

    private void grabar() {
        String sala = salaActual();
        if (sala == null || !salasUnidas.contains(sala)) { info("Unete a una sala."); return; }
        if (grabando) return;

        try {
            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(format);
            micLine.start();

            pcmBuffer = new ByteArrayOutputStream();
            grabando = true;

            btnGrabar.setDisable(true);
            btnDetener.setDisable(false);
            btnEnviarAudio.setDisable(true);
            lblEstadoGrab.setText("Grabando...");

            hiloGrabacion = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (grabando) {
                    int n = micLine.read(buf, 0, buf.length);
                    if (n > 0) pcmBuffer.write(buf, 0, n);
                }
            });
            hiloGrabacion.setDaemon(true);
            hiloGrabacion.start();

        } catch (Exception e) {
            alertError("No se pudo grabar: " + e.getMessage());
        }
    }

    private void detener() {
        if (!grabando) return;
        grabando = false;

        try {
            micLine.stop();
            micLine.close();
        } catch (Exception ignored) {}

        btnGrabar.setDisable(false);
        btnDetener.setDisable(true);

        try {
            byte[] pcm = pcmBuffer.toByteArray();
            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
            ultimoAudioWav = pcmToWav(pcm, format);

            lblEstadoGrab.setText("Audio listo (" + (ultimoAudioWav.length / 1024) + " KB)");
            btnEnviarAudio.setDisable(false);
        } catch (Exception e) {
            alertError("Error audio: " + e.getMessage());
        }
    }

    private void enviarAudio() {
        String sala = salaActual();
        if (sala == null || !salasUnidas.contains(sala)) { info("Unete a una sala"); return; }
        if (ultimoAudioWav == null) { info("No hay audio"); return; }

        String to = "*";
        if (chkPrivado.isSelected()) {
            ListView<String> lv = userViews.get(sala);
            String sel = (lv == null) ? null : lv.getSelectionModel().getSelectedItem();
            if (sel == null || sel.equals(username)) { info("Selecciona usuario"); return; }
            to = sel;
        }

        String id = UUID.randomUUID().toString();
        int total = (int) Math.ceil((double) ultimoAudioWav.length / AUDIO_CHUNK_RAW_BYTES);

        sendChatMessage(sala, "AUDIO_START", to, id + "," + total);

        for (int i = 0; i < total; i++) {
            int s = i * AUDIO_CHUNK_RAW_BYTES;
            int e = Math.min(s + AUDIO_CHUNK_RAW_BYTES, ultimoAudioWav.length);
            byte[] part = Arrays.copyOfRange(ultimoAudioWav, s, e);

            sendChatMessage(sala, "AUDIO_CHUNK", to,
                    id + "," + i + "," + Base64.getEncoder().encodeToString(part));

            try { Thread.sleep(2); } catch (Exception ignored) {}
        }

        sendChatMessage(sala, "AUDIO_END", to, id);

        lblEstadoGrab.setText("Audio enviado (" + total + " partes)");
        btnEnviarAudio.setDisable(true);
    }

    private void enviarSticker() {
        String sala = salaActual();
        if (sala == null || !salasUnidas.contains(sala)) { info("Unete a una sala."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Selecciona imagen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagen", "*.png", "*.jpg", "*.jpeg"));
        File f = fc.showOpenDialog(null);
        if (f == null) return;

        try {
            byte[] img = java.nio.file.Files.readAllBytes(f.toPath());

            String to = "*";
            if (chkPrivado.isSelected()) {
                ListView<String> lv = userViews.get(sala);
                String sel = (lv == null) ? null : lv.getSelectionModel().getSelectedItem();
                if (sel == null || sel.equals(username)) { info("Selecciona usuario para privado"); return; }
                to = sel;
            }

            String id = UUID.randomUUID().toString();
            int total = (int) Math.ceil((double) img.length / STICKER_CHUNK_RAW_BYTES);

            sendChatMessage(sala, "STICKER_START", to, id + "," + total);

            for (int i = 0; i < total; i++) {
                int s = i * STICKER_CHUNK_RAW_BYTES;
                int e = Math.min(s + STICKER_CHUNK_RAW_BYTES, img.length);
                byte[] part = Arrays.copyOfRange(img, s, e);

                sendChatMessage(sala, "STICKER_CHUNK", to,
                        id + "," + i + "," + Base64.getEncoder().encodeToString(part));

                Thread.sleep(2);
            }

            sendChatMessage(sala, "STICKER_END", to, id);

        } catch (Exception e) {
            alertError("Error sticker: " + e.getMessage());
        }
    }


        private void startCleanupThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(4000);
                    long now = System.currentTimeMillis();

                    for (Map.Entry<String, IncomingAssembly> en : new ArrayList<>(incoming.entrySet())) {
                        IncomingAssembly as = en.getValue();
                        if (now - as.lastUpdate > ASSEMBLY_TIMEOUT_MS) {
                            if (as.finalized.compareAndSet(false, true)) {
                                incoming.remove(en.getKey());
                                Platform.runLater(() -> addSystemMessage(as.sala, as.kind + " expiro (timeout UDP)."));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, "Cleanup");
        t.setDaemon(true);
        t.start();
    }

    private byte[] pcmToWav(byte[] pcm, AudioFormat format) throws IOException {
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeBytes("RIFF");
        dos.writeInt(Integer.reverseBytes(36 + pcm.length));
        dos.writeBytes("WAVE");

        dos.writeBytes("fmt ");
        dos.writeInt(Integer.reverseBytes(16));
        dos.writeShort(Short.reverseBytes((short) 1));
        dos.writeShort(Short.reverseBytes((short) channels));
        dos.writeInt(Integer.reverseBytes(sampleRate));
        dos.writeInt(Integer.reverseBytes(byteRate));
        dos.writeShort(Short.reverseBytes((short) blockAlign));
        dos.writeShort(Short.reverseBytes((short) bitsPerSample));

        dos.writeBytes("data");
        dos.writeInt(Integer.reverseBytes(pcm.length));
        dos.write(pcm);

        dos.flush();
        return out.toByteArray();
    }

    private void playWavBytes(byte[] wav) {
        if (wav == null || wav.length == 0) return;

        new Thread(() -> {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(wav);
                 AudioInputStream ais = AudioSystem.getAudioInputStream(bais)) {

                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                clip.start();

                long ms = Math.max(500, clip.getMicrosecondLength() / 1000);
                Thread.sleep(ms);

                clip.close();
            } catch (Exception e) {
                Platform.runLater(() -> alertError("No se pudo reproducir: " + e.getMessage()));
            }
        }, "AudioPlayer").start();
    }

    private void addMessage(String sala, ChatMessage m) {
        ensureTab(sala);
        ObservableList<ChatMessage> list = mensajesPorSala.get(sala);
        if (list != null) list.add(m);
        ListView<ChatMessage> lv = chatViews.get(sala);
        if (lv != null) lv.scrollTo(Math.max(0, list.size() - 1));
    }

    private void addSystemMessage(String sala, String txt) {
        addMessage(sala, new ChatMessage(sala, "SERVER", false, "*", "TEXT", "[SERVER] " + txt, null));
    }

    private String pedirUsuario() {
        TextInputDialog d = new TextInputDialog("");
        d.setTitle("Login");
        d.setHeaderText("Ingresa tu usuario");
        d.setContentText("Usuario:");
        Optional<String> r = d.showAndWait();
        if (r.isEmpty()) return null;
        String u = r.get().trim();
        return u.isEmpty() ? null : u;
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Info");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void alertError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void closeClient() {
        for (String sala : new HashSet<>(salasUnidas)) {
            try { sendToServer("LEAVE|" + sala + "|" + username); } catch (Exception ignored) {}
        }
        try { if (chatSocket != null) chatSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try {
            grabando = false;
            if (micLine != null) { micLine.stop(); micLine.close(); }
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        launch(args);
    }
}
