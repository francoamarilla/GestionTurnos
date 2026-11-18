# Recepta — branding

| Archivo | Uso |
|---|---|
| `logo-claro.svg` / `logo-oscuro.svg` | Logo horizontal (isotipo + nombre) para el portfolio, el login y el header del dashboard |
| `isotipo.svg` / `isotipo-oscuro.svg` | Solo el símbolo |
| `favicon.svg` | Favicon (se adapta solo al modo oscuro) |
| `favicon-32.png`, `apple-touch-icon.png` | Favicon para navegadores viejos e iOS |
| `avatar-bot.png` (640×640) | Foto de perfil del bot de WhatsApp y del chat web |
| `logo-claro.png` / `logo-oscuro.png` | Logo en PNG para el README de GitHub, LinkedIn y slides |
| `tokens.css` | Paleta lista para importar en el front |

## Paleta

| Token | Claro | Oscuro |
|---|---|---|
| Primario | `#2f5d50` | `#4f8f7b` |
| Primario hover | `#264c41` | `#5fa38d` |
| Texto | `#1f2a26` | `#f2efe8` |
| Fondo | `#f7f5f0` | `#1c2421` |
| Superficie | `#ffffff` | `#25302c` |
| Borde | `#d8d4cc` | `#3a4742` |
| Texto secundario | `#6b6b6b` | `#a3aba7` |

## Notas
- El nombre en los SVG usa la fuente Inter (o Segoe UI si no está instalada). Para que se vea idéntico en todos lados, usá los PNG o cargá Inter en el front.
- Dejá aire alrededor del logo: como mínimo, el ancho del destello.
- No cambies los colores del isotipo ni le agregues sombras o efectos.

## En el front (React)
```html
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
```
```js
import "./branding/tokens.css";
```
