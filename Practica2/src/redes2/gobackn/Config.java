package redes2.gobackn;

public class Config {
    public static final String SERVER_HOST = "127.0.0.1"; // IP del CLIENTE si van en 2 PCs
    public static final int SERVER_PORT = 7001;
    public static final int CLIENT_PORT = 7002;

    public static final int CHUNK_SIZE = 1400;          // bytes útiles por paquete
    public static final int WINDOW_SIZE = 10;           // tamaño de ventana Go-Back-N
    public static final int TIMEOUT_MS = 300;           // retransmisión
    public static final int SOCKET_RCV_TIMEOUT_MS = 200;// timeout receive() server
    public static final int MAX_PACKET_SIZE = 1600;     // header + datos + checksum
}
