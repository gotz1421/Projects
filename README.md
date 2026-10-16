# Cotizador Vidriera Gómez

App web (mobile first, también se ve bien en computadora) basada en el Google Sheets *Cotizador Vidriera Gómez*.

## Qué hace
- **Cotizar**: folio automático (`COT00221`, `COT00222`, …), fecha, cliente con sus datos (dirección, teléfono, correo), productos con descripción y precio automáticos, cantidad, total por partida, **subtotal, IVA 16 % y total**.
- **Clientes** y **Productos**: alta, edición, búsqueda y baja (sustituyen los Google Forms). Ya vienen cargados los datos de la hoja.
- **Historial**: cotizaciones guardadas; se pueden abrir, editar, volver a exportar o eliminar.
- **Exportar a PDF** con 4 plantillas: *Clásica* (como la hoja), *Moderna*, *Minimalista* (B/N) y *Ticket* (rollo de 80 mm).
- **Ajustes**: datos del negocio, prefijo y siguiente folio, % de IVA, plantilla por defecto, nota al pie y respaldo (descargar / restaurar).

Los datos se guardan en el navegador del dispositivo (localStorage). Para pasar los datos a otro equipo usa *Ajustes → Respaldo*.

## Publicar en Netlify
Es un sitio estático: no necesita build.

**Opción A — arrastrar y soltar:** en Netlify → *Add new site → Deploy manually*, arrastra la carpeta del proyecto (o el ZIP).

**Opción B — desde GitHub:** *Add new site → Import an existing project → GitHub*, elige este repositorio y la rama del cotizador.
Deja *Build command* vacío y *Publish directory* en `.` (ya viene en `netlify.toml`).

## Probar en local
```bash
python3 -m http.server 8080   # y abre http://localhost:8080
```
