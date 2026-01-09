package com.tienda.sockets;

import java.io.*;
import java.net.*;
import java.util.*;

public class Cliente {
    private static final String HOST = "127.0.0.1"; // Cambia por IP del servidor si es remoto
    private static final int PORT = 9999;

    public static void main(String[] args) {
        try (Socket socket = new Socket(HOST, PORT)) {
            System.out.println("Conectado a " + HOST + ":" + PORT);

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            Scanner sc = new Scanner(System.in);
            boolean salir = false;
            while (!salir) {
                mostrarMenu();
                System.out.print("Elige opción: ");
                String op = sc.nextLine().trim();
                switch (op) {
                    case "1": // Buscar por nombre o marca
                        System.out.print("Texto a buscar (nombre o marca): ");
                        String q = sc.nextLine();
                        enviar(oos, new Request(Accion.BUSCAR).put("q", q));
                        mostrarRespuestaLista(recibir(ois));
                        break;

                    case "2": // Listar por tipo
                        System.out.print("Tipo (periferico, display, computo, almacenamiento, audio...): ");
                        String tipo = sc.nextLine();
                        enviar(oos, new Request(Accion.LISTAR_TIPO).put("tipo", tipo));
                        mostrarRespuestaLista(recibir(ois));
                        break;

                    case "3": // Agregar al carrito
                        System.out.print("ID del artículo: ");
                        String id = sc.nextLine().trim();
                        System.out.print("Cantidad: ");
                        int cant = Integer.parseInt(sc.nextLine().trim());
                        enviar(oos, new Request(Accion.AGREGAR_CARRITO).put("id", id).put("cantidad", cant));
                        mostrarCarrito(recibir(ois));
                        break;

                    case "4": // Ver / editar carrito
                        enviar(oos, new Request(Accion.VER_CARRITO));
                        mostrarCarrito(recibir(ois));
                        System.out.print("¿Deseas editar? (s/n): ");
                        String ed = sc.nextLine().trim().toLowerCase();
                        if (ed.equals("s")) {
                            System.out.print("ID a editar: ");
                            String idE = sc.nextLine().trim();
                            System.out.print("Nueva cantidad (0 elimina): ");
                            int nuevaC = Integer.parseInt(sc.nextLine().trim());
                            enviar(oos, new Request(Accion.EDITAR_CARRITO).put("id", idE).put("cantidad", nuevaC));
                            mostrarCarrito(recibir(ois));
                        }
                        break;

                    case "5": // Eliminar del carrito
                        System.out.print("ID a eliminar: ");
                        String idDel = sc.nextLine().trim();
                        enviar(oos, new Request(Accion.ELIMINAR_DEL_CARRITO).put("id", idDel));
                        mostrarCarrito(recibir(ois));
                        break;

                    case "6": // Finalizar compra
                        enviar(oos, new Request(Accion.CHECKOUT));
                        Response r = recibir(ois);
                        if (r.ok && r.payload instanceof Ticket) {
                            Ticket t = (Ticket) r.payload;
                            System.out.println(t.toString());
                            guardarTicket(t);
                        } else {
                            System.out.println("No se pudo completar la compra: " + r.message);
                        }
                        break;

                    case "0": // Salir
                        enviar(oos, new Request(Accion.SALIR));
                        System.out.println(recibir(ois).message);
                        salir = true;
                        break;

                    default:
                        System.out.println("Opción no válida.");
                }
            }

        } catch (Exception e) {
            System.err.println("Error en cliente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void mostrarMenu() {
        System.out.println();
        System.out.println("===== TIENDA EN LÍNEA (Sockets bloqueantes) =====");
        System.out.println("1) Buscar artículos por nombre o marca");
        System.out.println("2) Listar artículos por tipo");
        System.out.println("3) Agregar artículo al carrito de compra");
        System.out.println("4) Editar contenido del carrito de compra");
        System.out.println("5) Eliminar del carrito");
        System.out.println("6) Finalizar compra y obtener ticket");
        System.out.println("0) Salir");
    }

    private static void enviar(ObjectOutputStream oos, Request req) throws IOException {
        oos.reset();
        oos.writeObject(req);
        oos.flush();
    }

    private static Response recibir(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        Object obj = ois.readObject();
        return (obj instanceof Response) ? (Response) obj : Response.fail("Respuesta inválida del servidor.");
    }

    @SuppressWarnings("unchecked")
    private static void mostrarRespuestaLista(Response r) {
        if (!r.ok) {
            System.out.println("Error: " + r.message);
            return;
        }
        List<Articulo> lista = (List<Articulo>) r.payload;
        if (lista == null || lista.isEmpty()) {
            System.out.println("No se encontraron artículos.");
            return;
        }
        System.out.println(String.format("%-6s | %-18s | %-12s | %-10s | %-8s | %s",
                "ID", "Nombre", "Marca", "Tipo", "Precio", "Exist"));
        System.out.println("------------------------------------------------------------------");
        for (Articulo a : lista) {
            System.out.println(String.format("%-6s | %-18s | %-12s | %-10s | $%,7.2f | %d", a.id, a.nombre, a.marca, a.tipo, a.precio, a.existencias));
        }
    }

    private static void mostrarCarrito(Response r) {
        if (!r.ok) {
            System.out.println("Error: " + r.message);
            return;
        }
        if (r.payload instanceof Carrito) {
            System.out.println(((Carrito) r.payload).toString());
        } else {
            System.out.println(r.message);
        }
    }

    private static void guardarTicket(Ticket t) {
        try {
            String nombre = "ticket_" + t.folio + ".txt";
            try (PrintWriter pw = new PrintWriter(new File(nombre), "UTF-8")) {
                pw.print(t.toString());
            }
            System.out.println("Ticket guardado en archivo: " + nombre);
        } catch (Exception e) {
            System.out.println("No se pudo guardar el ticket: " + e.getMessage());
        }
    }
}