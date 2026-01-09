package redes2.gobackn;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/** Controlador MP3 con JavaFX MediaPlayer. No tiene main: lo usa el Cliente. */
public class Mp3ControllerFx {
    private final String path;
    private volatile MediaPlayer player;

    private volatile double volume = 0.8;

    public Mp3ControllerFx(String path) {
        this.path = path;
        FxInit.init(); // aseguramos JavaFX iniciado
    }

    /** Reproduce desde el inicio (forzado). */
    public void playFromStart() throws Exception {
        stop(); // garantiza arranque limpio
        File f = new File(path);
        if (!f.exists()) throw new IllegalArgumentException("No existe el archivo: " + path);

        String uri = f.toURI().toString();
        runOnFxSync(() -> {
            Media media = new Media(uri);
            player = new MediaPlayer(media);
            player.setOnEndOfMedia(() -> {
                // Al terminar, dejamos el player listo para volver a reproducir desde inicio con playFromStart()
                try {
                    stop();
                } catch (Exception ignored) {}
            });
            player.play();
        });
    }

    /** Pausa. */
    public void pause() {
        runOnFx(() -> {
            if (player != null) player.pause();
        });
    }

    /** Reanuda. */
    public void resumePlay() {
        runOnFx(() -> {
            if (player != null) player.play();
        });
    }

    /** Detiene y resetea a inicio. */
    public void stop() {
        runOnFx(() -> {
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) {}
                try { player.dispose(); } catch (Exception ignored) {}
                player = null;
            }
        });
    }

    /** Reinicia: stop + playFromStart. */
    public void restart() throws Exception {
        stop();
        playFromStart();
    }

    /** Libera recursos. */
    public void close() {
        stop();
    }

    // ---- volumen ----
    public void setVolume(double v) {
        double nv = Math.max(0.0, Math.min(1.0, v));
        volume = nv;
        runOnFx(() -> { if (player != null) player.setVolume(volume); });
    }
    public double getVolume() { return volume; }

    public void volumeUp()  { setVolume(volume + 0.1); } // +10%
    public void volumeDown(){ setVolume(volume - 0.1); } // -10%

    public void toggleMute() {
        runOnFx(() -> {
            if (player != null) player.setMute(!player.isMute());
        });
    }

    /* ==================== utilidades FX ==================== */

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /** Ejecuta en FX y espera a que termine (para operaciones que deben ser sincrónicas). */
    private static void runOnFxSync(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException ignored) {}
    }
}
