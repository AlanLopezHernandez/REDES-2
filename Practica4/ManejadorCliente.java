import java.io.*;
import java.net.Socket;

public class ManejadorCliente implements Runnable {

    private Socket cliente;
    private String nombreServidor;

    public ManejadorCliente(Socket cliente, String nombreServidor) {
        this.cliente = cliente;
        this.nombreServidor = nombreServidor;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(cliente.getInputStream()));
            OutputStream out = cliente.getOutputStream();

            // Leer request line
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                cliente.close();
                return;
            }

            String[] partes = requestLine.split(" ");
            String metodo = partes[0];
            String recurso = partes[1];

            if (metodo.equals("OPTIONS")) {
                String respuesta =
                    "HTTP/1.1 200 OK\r\n" +
                    headersCORS() +
                    "\r\n";
                out.write(respuesta.getBytes());
                cliente.close();
                return;
            }


            switch (metodo) {
                case "GET":
                    manejarGET(out, recurso);
                    break;
                case "POST":
                    manejarPOST(in, out);
                    break;
                case "PUT":
                    manejarPUT(in, out, recurso);
                    break;
                case "DELETE":
                    manejarDELETE(out, recurso);
                    break;
                default:
                    enviarTexto(out, "Metodo HTTP no soportado");
            }

            cliente.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================
    // GET → servir archivos
    // ==========================
    private void manejarGET(OutputStream out, String recurso) throws IOException {

        File archivo = new File("." + recurso);

        if (!archivo.exists() || archivo.isDirectory()) {
            enviarTexto(out, "Archivo no encontrado");
            return;
        }

        byte[] datos = new FileInputStream(archivo).readAllBytes();
        String mime = obtenerMime(archivo.getName());
        String disposition = esInline(mime) ? "inline" : "attachment";

        String header =
                "HTTP/1.1 200 OK\r\n" +
                headersCORS() +
                "Content-Type: " + mime + "\r\n" +
                "Content-Disposition: " + disposition + "\r\n" +
                "X-Servidor: " + nombreServidor + "\r\n" +
                "Content-Length: " + datos.length + "\r\n\r\n";

        out.write(header.getBytes());
        out.write(datos);
    }

    // ==========================
    // POST → procesar datos
    // ==========================
    private void manejarPOST(BufferedReader in, OutputStream out) throws IOException {

        int contentLength = 0;
        String linea;

        // Leer headers
        while (!(linea = in.readLine()).isEmpty()) {
            if (linea.startsWith("Content-Length")) {
                contentLength = Integer.parseInt(linea.split(":")[1].trim());
            }
        }

        char[] buffer = new char[contentLength];
        in.read(buffer);
        String body = new String(buffer);

        enviarTexto(out, "POST recibido:\n" + body);
    }

    // ==========================
    // PUT → crear / sobrescribir archivo
    // ==========================
    private void manejarPUT(BufferedReader in, OutputStream out, String recurso)
            throws IOException {

        int contentLength = 0;
        String linea;

        while (!(linea = in.readLine()).isEmpty()) {
            if (linea.startsWith("Content-Length")) {
                contentLength = Integer.parseInt(linea.split(":")[1].trim());
            }
        }

        char[] buffer = new char[contentLength];
        in.read(buffer);
        String body = new String(buffer);

        File archivo = new File("." + recurso);
        try (FileWriter fw = new FileWriter(archivo)) {
            fw.write(body);
        }

        enviarTexto(out, "Archivo creado o actualizado: " + recurso);
    }

    // ==========================
    // DELETE → eliminar archivo real
    // ==========================
    private void manejarDELETE(OutputStream out, String recurso) throws IOException {

        File archivo = new File("." + recurso);

        if (!archivo.exists()) {
            enviarTexto(out, "Archivo no encontrado");
            return;
        }

        if (archivo.delete()) {
            enviarTexto(out, "Archivo eliminado correctamente");
        } else {
            enviarTexto(out, "No se pudo eliminar el archivo");
        }
    }

    // ==========================
    // UTILIDADES
    // ==========================
    private void enviarTexto(OutputStream out, String mensaje) throws IOException {

        String respuesta =
            "HTTP/1.1 200 OK\r\n" +
            headersCORS() +
            "Content-Type: text/plain\r\n" +
            "X-Servidor: " + nombreServidor + "\r\n" +
            "Content-Length: " + mensaje.length() + "\r\n\r\n" +
            mensaje;

        out.write(respuesta.getBytes());
    }


    private boolean esInline(String mime) {
        return mime.startsWith("text/")
                || mime.startsWith("image/")
                || mime.equals("application/pdf");
    }

    private String obtenerMime(String nombre) {

        nombre = nombre.toLowerCase();

        if (nombre.endsWith(".html") || nombre.endsWith(".htm"))
            return "text/html";
        if (nombre.endsWith(".txt"))
            return "text/plain";
        if (nombre.endsWith(".jpg") || nombre.endsWith(".jpeg"))
            return "image/jpeg";
        if (nombre.endsWith(".png"))
            return "image/png";
        if (nombre.endsWith(".ico"))
            return "image/x-icon";
        if (nombre.endsWith(".pdf"))
            return "application/pdf";
        if (nombre.endsWith(".doc"))
            return "application/msword";
        if (nombre.endsWith(".class"))
            return "application/java-vm";

        return "application/octet-stream";
    }

    private String headersCORS() {
    return
        "Access-Control-Allow-Origin: *\r\n" +
        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
        "Access-Control-Allow-Headers: Content-Type\r\n";
    }

    

}
