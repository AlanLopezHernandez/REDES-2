import java.net.*;
import java.util.concurrent.*;
import java.awt.Desktop;
import java.net.URI;

public class ServidorHTTP2 {

    private static final int PUERTO = 9000;
    private static final int TAM_POOL = 8;

    public static void main(String[] args) {
        try {
            ServerSocket servidor = new ServerSocket(PUERTO);

            ExecutorService pool =
                    Executors.newFixedThreadPool(TAM_POOL);

            System.out.println("Servidor HTTP SECUNDARIO en puerto " + PUERTO);

            // 🟢 ABRIR NAVEGADOR (solo para pruebas locales)
            abrirNavegador("http://localhost:" + PUERTO + "/archivo.txt");

            while (true) {
                Socket cliente = servidor.accept();
                pool.execute(
                    new ManejadorCliente(cliente, "SECUNDARIO")
                );
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================
    // ABRIR NAVEGADOR
    // ==========================
    private static void abrirNavegador(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("Navegador abierto en: " + url);
            }
        } catch (Exception e) {
            System.out.println("No se pudo abrir el navegador automáticamente");
        }
    }
}
