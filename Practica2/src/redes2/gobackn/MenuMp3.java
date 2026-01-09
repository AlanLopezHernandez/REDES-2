package redes2.gobackn;

import java.util.Scanner;

/** Menú de consola para controlar la reproducción con JavaFX MediaPlayer.
 *  No tiene main: el Cliente llama a new MenuMp3().run(ruta). */
public class MenuMp3 {

    /** Abre el menú bloqueante. Retorna cuando el usuario elige salir. */
    public void run(String ruta) throws Exception {
        Mp3ControllerFx ctrl = new Mp3ControllerFx(ruta);
        Scanner sc = new Scanner(System.in);
        try {
            boolean loop = true;
            while (loop) {
                System.out.println("\n========= MENU =========");
                System.out.println("[1] Reproducir (inicio)");
                System.out.println("[2] Pausar");
                System.out.println("[3] Reanudar");
                System.out.println("[4] Parar");
                System.out.println("[5] Reiniciar");
                System.out.println("[6] Volumen +");
                System.out.println("[7] Volumen -");
                System.out.println("[8] Mute On/Off");
                System.out.println("[9] Ver volumen actual");
                System.out.println("[0] Salir del menu");
                System.out.print("Opcion: ");
                String op = sc.nextLine().trim();

                switch (op) {
                    case "1":
                        ctrl.playFromStart();
                        System.out.println("Reproduciendo...");
                        break;
                    case "2":
                        ctrl.pause();
                        System.out.println("Pausado...");
                        break;
                    case "3":
                        ctrl.resumePlay();
                        System.out.println("Reanudando...");
                        break;
                    case "4":
                        ctrl.stop();
                        System.out.println("Detenido...");
                        break;
                    case "5":
                        ctrl.restart();
                        System.out.println("Reiniciando...");
                        break;
                    case "6":
                        ctrl.volumeUp();
                        System.out.printf("Volumen: %.0f%%%n", ctrl.getVolume()*100);
                        break;
                    case "7":
                        ctrl.volumeDown();
                        System.out.printf("Volumen: %.0f%%%n", ctrl.getVolume()*100);
                        break;
                    case "8":
                        ctrl.toggleMute();
                        System.out.println("Mute alternado");
                        break;
                    case "9":
                        System.out.printf("Volumen actual: %.0f%%%n", ctrl.getVolume()*100);
                        break;
                    case "0":
                        loop = false;
                        break;
                    default:
                        System.out.println("Opcion invalida");
                }
            }
        } finally {
            try { ctrl.close(); } catch (Exception ignored) {}
        }
    }
}