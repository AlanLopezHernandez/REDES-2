import java.io.Serializable;

public class Usuario implements Serializable {
    String nombre;
    String apaterno;
    String amaterno;
    transient String pwd;
    int edad;

    public Usuario(String nombre, String apaterno, String amaterno, String pwd, int edad) {
        this.nombre = nombre;
        this.apaterno = apaterno;
        this.amaterno = amaterno;
        this.pwd = pwd;
        this.edad = edad;
    }

    public String getNombre() {
        return nombre;
    }

    public String getApaterno() {
        return apaterno;
    }

    public String getAmaterno() {
        return amaterno;
    }

    public String getPwd() {
        return pwd;
    }

    public int getEdad() {
        return edad;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public void setApaterno(String apaterno) {
        this.apaterno = apaterno;
    }

    public void setAmaterno(String amaterno) {
        this.amaterno = amaterno;
    }

    public void setPwd(String pwd) {
        this.pwd = pwd;
    }

    public void setEdad(int edad) {
        this.edad = edad;
    }
}