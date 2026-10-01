# Turnos SaaS — Barbería con chatbot de WhatsApp

Plataforma SaaS multi-tenant de turnos para barberías, con dashboard web para el administrador y un chatbot de WhatsApp para el cliente final.

La especificación completa y vinculante del producto vive en `ESPECIFICACION-TECNICA.md` en la raíz del proyecto (documento interno, no versionado en este repo). Ese documento es la única fuente de verdad — cualquier duda de comportamiento se resuelve ahí, no acá.

## Estructura del repo

```
backend/     Java 21 + Spring Boot 4 (API REST, dominio, bot in-process)
frontend/    React (Vite + TS) — dashboard SPA
docker-compose.yml
```

## Stack

- **Backend**: Java 21, Spring Boot 4, PostgreSQL 16 con Row-Level Security para aislamiento multi-tenant, Flyway para migraciones.
- **Frontend**: React + TypeScript + Vite, sin librerías de UI/estado adicionales (`fetch` nativo, React Router).
- **Chatbot de WhatsApp**: motor de conversación corre in-process dentro del backend; el proveedor de WhatsApp Business API está abstraído detrás de la interfaz `WhatsAppProvider` (implementación real pendiente de elegir BSP — ver "Decisiones abiertas" abajo). En dev/test se usa `LoggingWhatsAppProvider`, que solo loguea los mensajes salientes.
- **Infra**: Docker Compose con 3 servicios (`postgres`, `backend`, `frontend` servido por nginx).

Ver sección 9 de la especificación técnica para el detalle completo y las razones de cada elección.

## Cómo levantar el proyecto

### Con Docker Compose (recomendado — stack completo)

```bash
docker compose up -d --build
```

Esto levanta los 3 servicios:

| Servicio | Puerto | Descripción |
|---|---|---|
| `postgres` | 5433 → 5432 | Postgres 16, con healthcheck |
| `backend` | 8080 | API REST Spring Boot |
| `frontend` | 8081 → 80 | nginx sirviendo el build estático; `/api/*` se reenvía al backend |

Abrir `http://localhost:8081` — el dashboard queda ahí, autenticado contra la API real vía el proxy de nginx.

`docker compose down` para bajar todo. `docker compose up -d --build` de nuevo cuando cambie código del backend o del frontend (el build ocurre dentro de la imagen, no hace falta compilar antes a mano).

### En desarrollo local (hot reload)

Backend y frontend corren cada uno por su cuenta; solo Postgres se levanta con Docker:

```bash
docker compose up -d postgres
cd backend && JWT_SECRET=$(openssl rand -base64 32) ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
```

En PowerShell, la variable va aparte: `$env:JWT_SECRET = "..."` antes del `./mvnw`. Es la única variable obligatoria (ver tabla abajo); Compose la fija sola, pero corriendo el backend a mano hay que pasarla.

El frontend en modo dev (`npm run dev`, puerto 5173) tiene un proxy propio (`vite.config.ts`) que reenvía `/api` a `http://localhost:8080`, así que no hace falta configurar CORS para desarrollar así.

### Alta del primer tenant

No hay autoregistro público (spec sección 2). El alta de un tenant es manual, vía un Super Admin:

1. Seedear un Super Admin al levantar el backend con las variables `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD` (ver tabla de variables de entorno abajo) — solo siembra la cuenta si no existe ya una con ese email.
2. Loguearse como ese Super Admin y usar `POST /api/admin/tenants` (o la pantalla mínima `/`) para crear el tenant, su primera sucursal y el primer usuario Admin, todo en un solo paso.

## Variables de entorno

`JWT_SECRET` es obligatoria: sin ella el backend no arranca. Compose fija un valor de dev, así que `docker compose up` funciona tal cual; correr el backend por fuera de Compose la pide. El resto tiene un default razonable para desarrollo local.

| Variable | Default | Uso |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/turnos` (Compose la fija a `postgres:5432`) | Conexión a Postgres |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `turnos_app` / `turnos_app` | Credenciales del rol de aplicación (no el superusuario — RLS depende de esto) |
| `JWT_SECRET` | **sin default** (Compose fija uno de dev) | Clave HMAC para firmar los JWT de sesión, en base64 de al menos 32 bytes: `openssl rand -base64 32`. El valor de Compose es público y sirve solo para desarrollo. |
| `JWT_EXPIRATION_SECONDS` | `86400` (24h) | Vencimiento del JWT — no hay refresh token (spec sección 11) |
| `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD` | vacío (no siembra nada) | Si ambas están seteadas y no existe ya una cuenta con ese email, se crea un Super Admin al arrancar |

## Decisiones abiertas pendientes

Estas quedan explícitamente sin resolver en el MVP (spec sección 13) — no fueron completadas por decisión propia durante el desarrollo, y no deberían resolverse unilateralmente en trabajo futuro sin confirmar con el negocio:

- **Proveedor de WhatsApp Business API (BSP)**: sin elegir (candidatos evaluados como referencia: 360dialog, Twilio, Gupshup). El código ya abstrae la integración detrás de `WhatsAppProvider` para no acoplarse a ninguno; falta implementar el adapter real una vez decidido.
- **Ventana de cancelación**: el default de 2 horas es configurable por tenant (`Tenant.cancellationWindowHours`), pero el valor "correcto" para el negocio no está confirmado.

Fuera de alcance para esta iteración (post-MVP, ver spec secciones 14–15): rol Manager, login propio del profesional, integración oficial de feriados AR, unificación automática de clientes, reprogramación masiva, seña/depósito online, waitlist, campañas de marketing, UI pulida de Super Admin, refresh token, multi-sucursal con UI de gestión, expansión a otros rubros.
