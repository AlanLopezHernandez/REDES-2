package redes2.gobackn;

import javafx.application.Platform;
import java.util.concurrent.atomic.AtomicBoolean;

/** Inicializa JavaFX en apps de consola (sin Application/Stage). */
public final class FxInit {
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private FxInit() {}

    /** Idempotente: si ya está iniciado, no hace nada. */
    public static void init() {
        if (started.get()) return;
        synchronized (FxInit.class) {
            if (started.get()) return;
            // Arranca el toolkit JavaFX sin abrir ventanas
            Platform.startup(() -> {});
            started.set(true);
        }
    }
}