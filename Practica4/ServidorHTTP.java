import java.net.*;
import java.util.concurrent.*;
import java.awt.Desktop;
import java.net.URI;

public class ServidorHTTP {

    private static final int PUERTO = 8000;
    private static final int TAM_POOL = 4;

    private static ThreadPoolExecutor executor =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(TAM_POOL);

    public static void main(String[] args) {
        try {
            ServerSocket servidor = new ServerSocket(PUERTO);
            System.out.println("Servidor HTTP PRINCIPAL en puerto " + PUERTO);

            while (true) {
                Socket cliente = servidor.accept();

                int activos = executor.getActiveCount();

                if (activos > TAM_POOL / 2) {
                    redirigir(cliente);
                } else {
                    executor.execute(
                        new ManejadorCliente(cliente, "PRINCIPAL")
                    );
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void redirigir(Socket cliente) {
        try {
            String respuesta =
                "HTTP/1.1 302 Found\r\n" +
                "Location: http://localhost:9000\r\n" +
                "X-Servidor: PRINCIPAL\r\n\r\n";

            cliente.getOutputStream().write(respuesta.getBytes());
            cliente.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
