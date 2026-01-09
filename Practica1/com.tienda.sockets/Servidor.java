package com.tienda.sockets;

import java.io.*;
import java.net.*;
import java.util.*;

public class Servidor {
    private static final int PORT = 9999;
    //Catálogo 
    private static final Map<String, Articulo> CATALOGO = new LinkedHashMap<>();
    // Lock para operaciones que modifican existencias en lote (checkout)
    private static final Object STOCK_LOCK = new Object();

    public static void main(String[] args) {
        seedCatalogo();
        System.out.println("Catálogo inicial:");
        CATALOGO.values().forEach(a -> System.out.println("  " + a));

        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Servidor iniciado en puerto " + PORT + ". Esperando clientes...");
            while (true) {
                Socket cl = server.accept();
                System.out.println("Cliente conectado desde " + cl.getInetAddress() + ":" + cl.getPort());
                new Thread(new ClienteHandler(cl)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Clase interna para manejar cada cliente 
    static class ClienteHandler implements Runnable {
        private final Socket socket;
        private ObjectOutputStream oos;
        private ObjectInputStream ois;
        private final Carrito carrito = new Carrito();

        ClienteHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                //Crear OOS antes que OIS para evitar deadlock
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();
                ois = new ObjectInputStream(socket.getInputStream());

                boolean activo = true;
                while (activo) {
                    Object obj = ois.readObject();
                    if (!(obj instanceof Request)) {
                        send(Response.fail("Petición inválida"));
                        continue;
                    }
                    Request req = (Request) obj;
                    switch (req.accion) {
                        case BUSCAR:
                            send(handleBuscar(req));
                            break;
                        case LISTAR_TIPO:
                            send(handleListarTipo(req));
                            break;
                        case AGREGAR_CARRITO:
                            send(handleAgregarCarrito(req));
                            break;
                        case EDITAR_CARRITO:
                            send(handleEditarCarrito(req));
                            break;
                        case ELIMINAR_DEL_CARRITO:
                            send(handleEliminarDelCarrito(req));
                            break;
                        case VER_CARRITO:
                            send(Response.ok(carrito));
                            break;
                        case CHECKOUT:
                            send(handleCheckout());
                            break;
                        case SALIR:
                            activo = false;
                            send(Response.ok("Sesión finalizada"));
                            break;
                        default:
                            send(Response.fail("Acción no soportada: " + req.accion));
                    }
                }
            } catch (EOFException eof) {
                System.out.println("Cliente desconectado: " + socket);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try { if (ois != null) ois.close(); } catch (Exception ignored) {}
                try { if (oos != null) oos.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
                System.out.println("Conexión cerrada: " + socket);
            }
        }

        private void send(Response r) throws IOException {
            oos.reset();
            oos.writeObject(r);
            oos.flush();
        }

        //Validar existencias antes de agregar artículos al carrito
        private Response handleBuscar(Request req) {
            String texto = Optional.ofNullable(req.<String>get("q")).orElse("").toLowerCase(Locale.ROOT);
            if (texto.isEmpty()) return Response.fail("Escribe un nombre o marca para buscar.");
            List<Articulo> result = new ArrayList<>();
            synchronized (CATALOGO) {
                for (Articulo a : CATALOGO.values()) {
                    if (a.nombre.toLowerCase(Locale.ROOT).contains(texto)|| a.marca.toLowerCase(Locale.ROOT).contains(texto)) {
                        result.add(cloneArticulo(a));
                    }
                }
            }
            return Response.ok(result);
        }

        private Response handleListarTipo(Request req) {
            String tipo = Optional.ofNullable(req.<String>get("tipo")).orElse("").toLowerCase(Locale.ROOT);
            if (tipo.isEmpty()) return Response.fail("Proporciona un tipo para listar.");
            List<Articulo> result = new ArrayList<>();
            synchronized (CATALOGO) {
                for (Articulo a : CATALOGO.values()) {
                    if (a.tipo.toLowerCase(Locale.ROOT).equals(tipo)) {
                        result.add(cloneArticulo(a));
                    }
                }
            }
            return Response.ok(result);
        }

        private Response handleAgregarCarrito(Request req) {
            String id = req.get("id");
            Integer cantidad = req.get("cantidad");
            if (id == null || cantidad == null || cantidad <= 0)
                return Response.fail("Datos inválidos para agregar al carrito.");
            synchronized (CATALOGO) {
                Articulo a = CATALOGO.get(id);
                if (a == null) return Response.fail("Artículo no encontrado.");
                int enCarrito = Optional.ofNullable(carrito.get(id)).map(it -> it.cantidad).orElse(0);
                if (cantidad + enCarrito > a.existencias)
                    return Response.fail("No hay existencias suficientes. Disponibles: " + a.existencias);
                carrito.put(a, cantidad);
                return Response.ok(carrito);
            }
        }

        private Response handleEditarCarrito(Request req) {
            String id = req.get("id");
            Integer nuevaCantidad = req.get("cantidad");
            if (id == null || nuevaCantidad == null || nuevaCantidad < 0)
                return Response.fail("Datos inválidos para editar el carrito.");
            synchronized (CATALOGO) {
                Articulo a = CATALOGO.get(id);
                if (a == null) return Response.fail("Artículo no encontrado.");
                if (nuevaCantidad == 0) {
                    carrito.remove(id);
                    return Response.ok(carrito);
                }
                if (nuevaCantidad > a.existencias)
                    return Response.fail("No hay existencias suficientes. Disponibles: " + a.existencias);
                carrito.setCantidad(a, nuevaCantidad);
                return Response.ok(carrito);
            }
        }

        private Response handleEliminarDelCarrito(Request req) {
            String id = req.get("id");
            if (id == null) return Response.fail("Proporciona un id válido.");
            carrito.remove(id);
            return Response.ok(carrito);
        }

        private Response handleCheckout() {
            synchronized (STOCK_LOCK) {
                for (ItemCarrito it : carrito.items()) {
                    Articulo a = CATALOGO.get(it.articuloId);
                    if (a == null) return Response.fail("Artículo no encontrado: " + it.articuloId);
                    if (it.cantidad > a.existencias)
                        return Response.fail("Existencias insuficientes para " + a.nombre);
                }
                for (ItemCarrito it : carrito.items()) {
                    Articulo a = CATALOGO.get(it.articuloId);
                    a.existencias -= it.cantidad;
                }
                String folio = "T" + System.currentTimeMillis() + "-" + (int) (Math.random() * 900 + 100);
                Ticket t = new Ticket(folio, new Date(), new ArrayList<>(carrito.items()), carrito.total());
                carrito.clear();
                return Response.ok(t);
            }
        }

        private Articulo cloneArticulo(Articulo a) {
            return new Articulo(a.id, a.nombre, a.marca, a.tipo, a.precio, a.existencias);
        }
    }

    //MÉTODO SEEDCATALOGO EN EL NIVEL DE LA CLASE, NO DENTRO DE CATCH
    private static void seedCatalogo() {
        synchronized (CATALOGO) {
            CATALOGO.put("A001", new Articulo("A001", "Mouse óptico", "Logitech", "periferico", 249.00, 15));
            CATALOGO.put("A002", new Articulo("A002", "Teclado mecánico", "Redragon", "periferico", 899.00, 8));
            CATALOGO.put("A003", new Articulo("A003", "Monitor 24\"", "Samsung", "display", 2899.00, 5));
            CATALOGO.put("A004", new Articulo("A004", "Laptop 14\"", "Lenovo", "computo", 12999.00, 4));
            CATALOGO.put("A005", new Articulo("A005", "SSD 1TB", "Kingston", "almacenamiento", 1599.00, 10));
            CATALOGO.put("A006", new Articulo("A006", "Audífonos BT", "Sony", "audio", 1999.00, 7));
            CATALOGO.put("A007", new Articulo("A007", "Webcam HD", "Logitech", "periferico", 749.00, 12));
            CATALOGO.put("A008", new Articulo("A008", "Tarjeta de video", "NVIDIA", "computo", 6999.00, 2));
        }
    }
}