import { useState } from "react";

const SERVIDOR = "http://localhost:8000";

function App() {

  const [ruta, setRuta] = useState("");
  const [contenido, setContenido] = useState("");
  const [resultado, setResultado] = useState("");

  // ======================
  // GET → abrir archivo
  // ======================
  const hacerGET = async () => {
    if (!ruta) return alert("Escribe la ruta del archivo");

    // Abrir archivo en otra pestaña
    window.open(SERVIDOR + ruta, "_blank");

    const res = await fetch(SERVIDOR + ruta);
    mostrarRespuesta(res);
  };

  // ======================
  // PUT → crear / modificar archivo
  // ======================
  const hacerPUT = async () => {
    if (!ruta) return alert("Escribe la ruta del archivo");

    const res = await fetch(SERVIDOR + ruta, {
      method: "PUT",
      body: contenido
    });

    mostrarRespuesta(res);
  };

  // ======================
  // POST → enviar datos
  // ======================
  const hacerPOST = async () => {
    const res = await fetch(SERVIDOR, {
      method: "POST",
      headers: {
        "Content-Type": "text/plain"
      },
      body: contenido
    });

    mostrarRespuesta(res);
  };

  // ======================
  // DELETE → eliminar archivo
  // ======================
  const hacerDELETE = async () => {
    if (!ruta) return alert("Escribe la ruta del archivo");

    const res = await fetch(SERVIDOR + ruta, {
      method: "DELETE"
    });

    mostrarRespuesta(res);
  };

  // ======================
  // Mostrar respuesta HTTP
  // ======================
  const mostrarRespuesta = async (res) => {
    let texto = `STATUS: ${res.status}\n\nHEADERS:\n`;

    res.headers.forEach((v, k) => {
      texto += `${k}: ${v}\n`;
    });

    try {
      const body = await res.text();
      texto += `\nBODY:\n${body}`;
    } catch {}

    setResultado(texto);
  };

  return (
    <div className="container">
      <h1>Cliente HTTP - Práctica de Redes</h1>

      <div className="card">
        <label>Ruta del archivo (GET / PUT / DELETE)</label>
        <input
          placeholder="/archivo.txt"
          value={ruta}
          onChange={e => setRuta(e.target.value)}
        />

        <label>Contenido (PUT / POST)</label>
        <textarea
          placeholder="Escribe aquí el contenido..."
          value={contenido}
          onChange={e => setContenido(e.target.value)}
        />

        <div className="buttons">
          <button className="get" onClick={hacerGET}>GET</button>
          <button className="put" onClick={hacerPUT}>PUT</button>
          <button className="post" onClick={hacerPOST}>POST</button>
          <button className="delete" onClick={hacerDELETE}>DELETE</button>
        </div>
      </div>

      <div className="card">
        <h3>Resultado del Protocolo HTTP</h3>
        <pre>{resultado}</pre>
      </div>
    </div>
  );
}

export default App;
