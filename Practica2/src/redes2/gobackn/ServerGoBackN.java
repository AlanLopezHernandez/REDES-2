package redes2.gobackn;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.util.Arrays;

public class ServerGoBackN {

    public static void main(String[] args) throws Exception {
        // Ruta del MP3 a enviar (ajústala a tu archivo real)
        String filePath = "C:\\Users\\Mariam\\Documents\\REDES2\\practica2\\Practic2\\c2.mp3";
        File f = new File(filePath);
        if (!f.exists()) {
            System.err.println("No existe el archivo: " + filePath);
            return;
        }

        byte[] all = readAll(f);
        int total = (int) Math.ceil(all.length / (double) Config.CHUNK_SIZE);

        DatagramSocket sock = new DatagramSocket(Config.SERVER_PORT);
        sock.setSoTimeout(Config.SOCKET_RCV_TIMEOUT_MS);

        // IMPORTANTE:
        // - Si todo corre en la misma PC, deja 127.0.0.1
        // - Si son 2 PCs, usa aquí la IP del CLIENTE (donde corre ClientGoBackN)
        InetAddress clientAddr = InetAddress.getByName(Config.SERVER_HOST);
        int clientPort = Config.CLIENT_PORT;

        int base = 0;            // primer no ACKed
        int nextSeq = 0;         // siguiente por enviar
        long lastSendTime = 0;
        boolean done = false;

        System.out.printf("Sirviendo %s, %d bytes en %d paquetes%n",
                f.getName(), all.length, total);

        while (!done) {
            // 1) Enviar nuevos dentro de la ventana
            while (nextSeq < base + Config.WINDOW_SIZE && nextSeq < total) {
                Packet p = buildPacket(f.getName(), nextSeq, total, all);
                byte[] bytes = p.toBytes();
                DatagramPacket dp = new DatagramPacket(bytes, bytes.length, clientAddr, clientPort);
                sock.send(dp);
                if (base == nextSeq) lastSendTime = System.currentTimeMillis();
                System.out.println("Enviado seq=" + nextSeq + "/" + (total - 1));
                nextSeq++;
            }

            // 2) Esperar ACK
            try {
                byte[] buf = new byte[64];
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                sock.receive(recv); // bloqueante con timeout
                Packet.Ack ack = Packet.Ack.fromBytes(recv.getData(), recv.getLength());
                if (ack.nextExpected > base) {
                    base = ack.nextExpected;
                    System.out.println("ACK nextExpected=" + base);
                    if (base == nextSeq) {
                        // ventana vacía
                        lastSendTime = 0;
                    } else {
                        lastSendTime = System.currentTimeMillis();
                    }
                }
            } catch (SocketTimeoutException te) {
                // 3) Timeout: retransmitir desde base
                if (base < nextSeq && (System.currentTimeMillis() - lastSendTime) >= Config.TIMEOUT_MS) {
                    System.out.println("TIMEOUT: retransmitiendo desde base=" + base);
                    for (int s = base; s < nextSeq; s++) {
                        Packet p = buildPacket(f.getName(), s, total, all);
                        byte[] bytes = p.toBytes();
                        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, clientAddr, clientPort);
                        sock.send(dp);
                    }
                    lastSendTime = System.currentTimeMillis();
                }
            }

            if (base >= total) {
                System.out.println("Transferencia completa");
                done = true;
            }
        }
        sock.close();
    }

    private static Packet buildPacket(String name, int seq, int total, byte[] all) throws IOException {
        int off = seq * Config.CHUNK_SIZE;
        int remaining = Math.max(0, all.length - off);
        int len = Math.min(Config.CHUNK_SIZE, remaining);
        Packet p = new Packet();
        p.archivo = name;
        p.seq = seq;
        p.total = total;
        p.len = len;
        p.data = Arrays.copyOfRange(all, off, off + len);
        return p;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.readAllBytes();
        }
    }
}
