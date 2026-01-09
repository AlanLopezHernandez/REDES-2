import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Servidor UDP que SOLO maneja:
 * - JOIN|sala|usuario
 * - LEAVE|sala|usuario
 *
 * Mantiene un mapa sala -> usuarios (con IP/puerto).
 * Cada vez que cambia la sala, envía:
 *   USERLIST|sala|user1,user2,...
 * a todos los clientes registrados en esa sala.
 */
public class servidor {

    private static final int SERVER_PORT = 5555;
    private static final int MAX_PACKET_SIZE = 65507; // Máximo UDP

    // Información de un cliente (IP + puerto + nombre)
    private static class ClientInfo {
        final InetAddress address;
        final int port;
        final String username;

        ClientInfo(InetAddress address, int port, String username) {
            this.address = address;
            this.port = port;
            this.username = username;
        }

        // Igualdad por dirección y puerto (un endpoint)
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClientInfo)) return false;
            ClientInfo that = (ClientInfo) o;
            return port == that.port &&
                    Objects.equals(address, that.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, port);
        }
    }

    // sala -> conjunto de clientes
    private final Map<String, Set<ClientInfo>> salas = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        servidor server = new servidor();
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Error en servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() throws IOException {
        System.out.println("Servidor UDP iniciado en puerto " + SERVER_PORT);
        try (DatagramSocket socket = new DatagramSocket(SERVER_PORT)) {
            byte[] buffer = new byte[MAX_PACKET_SIZE];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(socket, packet);
            }
        }
    }

    private void handlePacket(DatagramSocket socket, DatagramPacket packet) {
        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String[] parts = msg.split("\\|", 3);
        if (parts.length < 3) {
            System.out.println("Paquete inválido: " + msg);
            return;
        }

        String command = parts[0];
        String sala = parts[1];
        String usuario = parts[2];
        InetAddress addr = packet.getAddress();
        int port = packet.getPort();

        System.out.printf("Recibido: %s de %s (%s:%d)%n", msg, usuario, addr.getHostAddress(), port);

        switch (command) {
            case "JOIN":
                processJoin(socket, sala, usuario, addr, port);
                break;
            case "LEAVE":
                processLeave(socket, sala, usuario, addr, port);
                break;
            default:
                System.out.println("Comando no soportado: " + command);
        }
    }

    private void processJoin(DatagramSocket socket, String sala, String usuario,
                             InetAddress addr, int port) {
        lock.writeLock().lock();
        try {
            Set<ClientInfo> clientes = salas.computeIfAbsent(sala, k -> new HashSet<>());
            ClientInfo ci = new ClientInfo(addr, port, usuario);
            clientes.add(ci);
            System.out.printf("Usuario %s se unió a sala %s. Total: %d usuarios%n",
                    usuario, sala, clientes.size());
        } finally {
            lock.writeLock().unlock();
        }

        // Después de actualizar, enviamos la lista a todos
        sendUserList(socket, sala);
    }

    private void processLeave(DatagramSocket socket, String sala, String usuario,
                              InetAddress addr, int port) {
        lock.writeLock().lock();
        try {
            Set<ClientInfo> clientes = salas.get(sala);
            if (clientes != null) {
                ClientInfo ci = new ClientInfo(addr, port, usuario);
                clientes.remove(ci);
                System.out.printf("Usuario %s salió de sala %s. Total: %d usuarios%n",
                        usuario, sala, clientes.size());
                if (clientes.isEmpty()) {
                    salas.remove(sala);
                    System.out.printf("Sala %s ahora está vacía y se eliminó.%n", sala);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Enviamos lista actualizada (si la sala sigue existiendo)
        sendUserList(socket, sala);
    }

    private void sendUserList(DatagramSocket socket, String sala) {
        List<ClientInfo> destinos;
        List<String> usuarios;

        lock.readLock().lock();
        try {
            Set<ClientInfo> clientes = salas.get(sala);
            if (clientes == null || clientes.isEmpty()) {
                System.out.printf("Sala %s vacía, no se envía lista.%n", sala);
                return;
            }
            destinos = new ArrayList<>(clientes);
            usuarios = new ArrayList<>();
            for (ClientInfo ci : clientes) {
                usuarios.add(ci.username);
            }
        } finally {
            lock.readLock().unlock();
        }

        String lista = String.join(",", usuarios);
        String payload = "USERLIST|" + sala + "|" + lista;
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);

        for (ClientInfo ci : destinos) {
            try {
                DatagramPacket p = new DatagramPacket(
                        data, data.length, ci.address, ci.port
                );
                socket.send(p);
            } catch (IOException e) {
                System.err.printf("Error enviando USERLIST a %s:%d - %s%n",
                        ci.address.getHostAddress(), ci.port, e.getMessage());
            }
        }

        System.out.printf("Enviada USERLIST de sala %s: %s%n", sala, lista);
    }
}
