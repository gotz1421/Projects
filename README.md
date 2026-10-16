# Cotizador Vidriera Gómez

App web (mobile first, también se ve bien en computadora) basada en el Google Sheets *Cotizador Vidriera Gómez*.

## Qué hace
- **Cotizar**: folio automático (`COT00221`, `COT00222`, …), fecha, cliente con sus datos (dirección, teléfono, correo), productos con descripción y precio automáticos, cantidad, total por partida, **subtotal, IVA 16 % y total**.
- **Clientes** y **Productos**: alta, edición, búsqueda y baja (sustituyen los Google Forms). Ya vienen cargados los datos de la hoja.
- **Historial**: cotizaciones guardadas; se pueden abrir, editar, volver a exportar o eliminar.
- **Exportar a PDF** con 4 plantillas: *Clásica* (como la hoja), *Moderna*, *Minimalista* (B/N) y *Ticket* (rollo de 80 mm).
- **Ajustes**: datos del negocio, prefijo y siguiente folio, % de IVA, plantilla por defecto, nota al pie y respaldo (descargar / restaurar).

- **Cuentas**: cada usuario entra con correo y contraseña y solo ve sus propios datos (Firebase Authentication + Cloud Firestore, plan gratis Spark). Las cuentas nuevas empiezan con el catálogo de productos de ejemplo.
  Si en el navegador había datos de la versión anterior (sin cuentas), la app ofrece pasarlos a la cuenta al crearla.

## Configurar Firebase (una sola vez)
1. Entra a <https://console.firebase.google.com> → **Agregar proyecto** (puedes desactivar Google Analytics).
2. **Authentication → Comenzar → Correo electrónico/contraseña → Habilitar**.
3. **Firestore Database → Crear base de datos** (modo producción, ubicación cercana, p. ej. `us-central` o `northamerica-south1`).
4. En **Firestore → Reglas**, pega el contenido de [`firestore.rules`](firestore.rules) y pulsa **Publicar**. Esto es lo que impide que un usuario vea los datos de otro.
5. **⚙ Configuración del proyecto → General → Tus apps → `</>` (Web)**: registra la app y copia el objeto `firebaseConfig` en [`firebase-config.js`](firebase-config.js).
6. **Authentication → Configuración → Dominios autorizados**: agrega tu dominio de Netlify (p. ej. `tu-sitio.netlify.app`).

Los datos de `firebaseConfig` no son secretos (van dentro de la app web); la seguridad la dan las reglas del paso 4.

## Publicar en Netlify
Es un sitio estático: no necesita build.

**Opción A — arrastrar y soltar:** en Netlify → *Add new site → Deploy manually*, arrastra la carpeta del proyecto (o el ZIP).

**Opción B — desde GitHub:** *Add new site → Import an existing project → GitHub*, elige este repositorio y la rama del cotizador.
Deja *Build command* vacío y *Publish directory* en `.` (ya viene en `netlify.toml`).

## Probar en local
```bash
python3 -m http.server 8080   # y abre http://localhost:8080
```
