package redes2.gobackn;

import java.io.*;

public class Packet {
    public static final int MAGIC = 0x514D5051; // "QMPQ" cualquiera
    public static final short VERSION = 1;

    // +Archivo +No paquete +Total paquete +tam arreglo +{datos}
    public String archivo;
    public int seq;
    public int total;
    public int len;
    public byte[] data;

    // ---- Serialización ----
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream(Config.MAX_PACKET_SIZE);
        DataOutputStream dos = new DataOutputStream(bout);

        dos.writeInt(MAGIC);
        dos.writeShort(VERSION);
        dos.writeUTF(archivo == null ? "" : archivo);
        dos.writeInt(seq);
        dos.writeInt(total);
        dos.writeInt(len);
        if (data != null && len > 0) dos.write(data, 0, len);

        // checksum sobre el contenido SIN el propio checksum
        byte[] body = bout.toByteArray();
        int sum = checksum(body, 0, body.length);
        dos.writeInt(sum); // se añade al final

        return bout.toByteArray();
    }

    public static Packet fromBytes(byte[] buf, int length) throws IOException {
        if (length < 4 + 2 + 2 + 4 + 4 + 4 + 4) // muy mínimo + checksum
            throw new IOException("Paquete demasiado corto");

        // validar checksum
        int got = bytesToInt(buf, length - 4);
        int sum = checksum(buf, 0, length - 4);
        if (sum != got) throw new IOException("Checksum invalido");

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf, 0, length - 4));
        int magic = dis.readInt();
        if (magic != MAGIC) throw new IOException("MAGIC invalido");
        short ver = dis.readShort();
        if (ver != VERSION) throw new IOException("VERSION invalida");

        Packet p = new Packet();
        p.archivo = dis.readUTF();
        p.seq = dis.readInt();
        p.total = dis.readInt();
        p.len = dis.readInt();
        if (p.len < 0 || p.len > Config.CHUNK_SIZE) throw new IOException("len invalido");
        p.data = new byte[p.len];
        dis.readFully(p.data);
        return p;
    }

    // ---- ACK cumulativo (Go-Back-N) ----
    public static class Ack {
        public static final int MAGIC_ACK = 0x41434B31; // "ACK1"
        public int nextExpected;

        public byte[] toBytes() throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream(16);
            DataOutputStream dos = new DataOutputStream(bout);
            dos.writeInt(MAGIC_ACK);
            dos.writeInt(nextExpected);
            byte[] body = bout.toByteArray();
            int sum = checksum(body, 0, body.length);
            dos.writeInt(sum);
            return bout.toByteArray();
        }

        public static Ack fromBytes(byte[] buf, int len) throws IOException {
            if (len < 12) throw new IOException("ACK muy corto");
            int got = bytesToInt(buf, len - 4);
            int sum = checksum(buf, 0, len - 4);
            if (sum != got) throw new IOException("ACK checksum invalido");
            int magic = bytesToInt(buf, 0);
            if (magic != MAGIC_ACK) throw new IOException("ACK MAGIC invalido");
            Ack a = new Ack();
            a.nextExpected = bytesToInt(buf, 4);
            return a;
        }
    }

    // ---- util ----
    private static int checksum(byte[] a, int off, int len) {
        long s = 0;
        for (int i = off; i < off + len; i++) s = (s + (a[i] & 0xFF)) & 0x7FFFFFFF;
        return (int) s;
    }
    private static int bytesToInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) |
               ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
