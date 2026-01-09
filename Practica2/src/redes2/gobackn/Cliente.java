package redes2.gobackn;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

/** Cliente Go-Back-N: recibe el MP3 por UDP, guarda el archivo y abre el menú (JavaFX). */
public class Cliente {

    public static void main(String[] args) throws Exception {
        DatagramSocket sock = new DatagramSocket(Config.CLIENT_PORT);
        sock.setSoTimeout(0); // bloqueante puro

        Map<Integer, byte[]> buffer = new HashMap<>();
        String archivo = "c3.mp3";   // fija el nombre final aquí si lo prefieres
        int expected = 0;
        int total = Integer.MAX_VALUE;

        InetAddress serverAddr = InetAddress.getByName(Config.SERVER_HOST);
        int serverPort = Config.SERVER_PORT;

        System.out.println("Cliente escuchando...");

        while (expected < total) {
            byte[] raw = new byte[Config.MAX_PACKET_SIZE];
            DatagramPacket dp = new DatagramPacket(raw, raw.length);
            sock.receive(dp);

            try {
                Packet p = Packet.fromBytes(dp.getData(), dp.getLength());
                // Si quieres usar el nombre que envía el servidor, descomenta:
                // if (expected == 0 && p.archivo != null && !p.archivo.isEmpty()) archivo = p.archivo;

                total = p.total;

                if (p.seq == expected) {
                    buffer.put(p.seq, p.data);
                    while (buffer.containsKey(expected)) expected++;
                }

                // Envío de ACK cumulativo
                Packet.Ack ack = new Packet.Ack();
                ack.nextExpected = expected;
                byte[] ackBytes = ack.toBytes();
                DatagramPacket ackDp = new DatagramPacket(ackBytes, ackBytes.length, serverAddr, serverPort);
                sock.send(ackDp);

            } catch (IOException bad) {
                // paquete corrupto => ignorar (el servidor reintentará)
            }
        }
        sock.close();

        // Ensamblar archivo final
        writeInOrder(archivo, buffer);
        System.out.println("Archivo guardado: " + archivo);

        // Abrir menú de reproducción (JavaFX) dentro del cliente
        System.out.println("Abriendo menu de reproduccion ...");
        try {
            new MenuMp3().run(archivo);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Menu cerrado Cliente finalizado");
    }

    private static void writeInOrder(String archivo, Map<Integer, byte[]> buffer) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(archivo)) {
            for (int i = 0; i < buffer.size(); i++) {
                fos.write(buffer.get(i));
            }
        }
    }
}