# Deploy de la demo pública

Guía de los pasos **manuales** para poner la demo online en planes gratuitos,
sin tarjeta. El código ya está listo; esto es todo trabajo de consola web.

El reparto es: **Neon** la base, **Render** el backend, **Vercel** el
frontend, **UptimeRobot** el ping que mantiene despierto a Render.

> **Una sola versión del código.** No hay rama ni copia "demo". El modo demo
> es puramente configuración: lo activa `SPRING_PROFILES_ACTIVE=demo` en
> Render y nada más. El mismo commit sirve para un deploy de producción con
> otra base y otras variables (ver la última sección).

---

## Antes de empezar

Vas a necesitar cuentas en [neon.tech](https://neon.tech),
[render.com](https://render.com), [vercel.com](https://vercel.com) y
[uptimerobot.com](https://uptimerobot.com). Las cuatro tienen plan gratuito
sin tarjeta.

Elegí ya dos valores y tenelos a mano, porque se repiten en varios pasos:

| Valor | Ejemplo | Dónde se usa |
|---|---|---|
| Email del admin de demo | `demo@barberiademo.ar` | Render y Vercel |
| Contraseña del admin de demo | algo simple y público, es una demo | Render y Vercel |

---

## Paso 1 — Base de datos en Neon

1. Crear un proyecto nuevo. **Región: AWS US East (Ohio o N. Virginia)** —
   tiene que quedar cerca de Render Virginia, porque cada consulta cruza la
   red y la latencia se nota en la demo.
2. Postgres 16 o superior, nombre de base `neondb` (el default está bien).
3. En **Connection Details**, copiar la connection string y asegurarse de
   elegir la opción **Direct connection**, no "Pooled connection".

   La directa se parece a:

   ```
   postgresql://neondb_owner:XXXX@ep-algo-123456.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```

   La pooled tiene `-pooler` en el host. **No uses esa.** El porqué está más
   abajo, en "Por qué la conexión directa".

4. De esa string sacá las tres piezas que necesita Render:

   | Variable de Render | De dónde sale |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://ep-algo-123456.us-east-2.aws.neon.tech/neondb?sslmode=require` (ojo: prefijo `jdbc:`, y **sin** usuario ni contraseña adentro) |
   | `SPRING_DATASOURCE_USERNAME` | `neondb_owner` |
   | `SPRING_DATASOURCE_PASSWORD` | lo que va entre `:` y `@` |

No hace falta crear tablas ni roles a mano: Flyway corre todas las migraciones
solo, incluida la extensión `btree_gist` (migración `V1`).

---

## Paso 2 — Backend en Render

1. **New → Blueprint**, apuntado a este repo. Render lee `render.yaml` y
   propone el servicio `turnos-backend` (Docker, plan free, Virginia).
2. Cargar las variables marcadas como "sync: false", que Render pide una por
   una. La tabla completa está al final de este documento.
3. `JWT_SECRET` y `DEMO_RESET_TOKEN` los genera Render solo
   (`generateValue: true`): no los escribas vos.
4. `CORS_ALLOWED_ORIGINS` todavía no lo sabés — dejalo en blanco en este
   paso y volvé después del paso 3, cuando Vercel te dé el dominio.
5. Deploy. El primer build tarda bastante (compila el backend dentro de la
   imagen). Cuando termine, anotá la URL del servicio:
   `https://turnos-backend-xxxx.onrender.com`.
6. Comprobar que arrancó:

   ```bash
   curl https://turnos-backend-xxxx.onrender.com/actuator/health/liveness
   # {"status":"UP"}
   ```

### Lo que pasa en el primer arranque

Con el perfil `demo` activo, al levantar la app se borra y regenera el tenant
"Barbería Demo": 4 profesionales, 7 servicios, 40 clientes y unos cuantos
cientos de turnos, desde 60 días atrás hasta 14 adelante, con fechas
relativas al día de hoy. Eso significa que **cada redeploy refresca las
fechas**, así que la demo nunca se ve vieja.

---

## Paso 3 — Frontend en Vercel

1. **Add New → Project**, importar este repo.
2. **Root Directory: `frontend`**. Vercel detecta Vite solo; `vercel.json` ya
   trae el rewrite a `index.html` para que React Router sobreviva a un F5 en
   `/agenda`.
3. Variables de entorno (las tres son de build, no de runtime):

   | Variable | Valor |
   |---|---|
   | `VITE_API_BASE_URL` | `https://turnos-backend-xxxx.onrender.com/api` |
   | `VITE_DEMO_EMAIL` | el email de demo |
   | `VITE_DEMO_PASSWORD` | la contraseña de demo |

   Las dos últimas son las que hacen aparecer el cartel con las credenciales
   en la pantalla de login. Si no las definís, no aparece nada — que es lo
   que querés en cualquier build que no sea la demo.

4. Deploy, y anotá el dominio: `https://turnos-demo.vercel.app`.

### Volver a Render a cerrar el círculo

Ahora sí, en Render: `CORS_ALLOWED_ORIGINS = https://turnos-demo.vercel.app`
(sin barra al final) y redeploy. Sin esto el navegador bloquea cada llamada,
porque el frontend y la API están en dominios distintos.

---

## Paso 4 — UptimeRobot

El plan free de Render **duerme el servicio a los 15 minutos sin tráfico**, y
despertarlo tarda cerca de un minuto. Para una demo que le vas a pasar a un
cliente, ese minuto en blanco es el problema.

1. **Add New Monitor**, tipo **HTTP(s)**.
2. URL: `https://turnos-backend-xxxx.onrender.com/actuator/health/liveness`
3. Intervalo: **10 minutos**.

**Tiene que ser `/liveness`, no `/actuator/health`.** El health completo
consulta Postgres, así que despertaría también a Neon cada 10 minutos y te
comería las horas de cómputo del plan free. `/liveness` solo responde que el
proceso está vivo, sin tocar la base.

Con esto Render queda despierto y Neon duerme hasta que alguien de verdad
entra a la demo. La primera consulta después de un rato tarda unos segundos
mientras Neon se despierta: es esperable, y por eso el `connection-timeout`
del pool está en 30 segundos.

---

## Paso 5 — Asistente de IA (opcional)

Sin este paso la demo funciona igual: el chat público (`/chat/demo`) atiende
con el menú de botones.

1. Generá un secreto compartido: `openssl rand -hex 32`.
2. En Render, el blueprint ya declara **`turnos-ai`** (`ai-service/`). Cargale
   `ANTHROPIC_API_KEY`, `AI_SERVICE_TOKEN` (el secreto) y `CORE_BASE_URL` (la
   URL pública de `turnos-backend`, sin barra final).
3. En `turnos-backend`: `AI_SERVICE_URL` (la URL pública de `turnos-ai`) y el
   **mismo** `AI_SERVICE_TOKEN`. Redeploy.
4. Opcional, captcha: creá un widget en Cloudflare Turnstile. El secret va en
   `TURNSTILE_SECRET` (Render) y la site key en `VITE_TURNSTILE_SITE_KEY`
   (Vercel, con rebuild).
5. UptimeRobot: otro monitor a `https://turnos-ai-xxxx.onrender.com/health`.
   Si no, el primer mensaje después de 15 minutos espera a que despierte.

**Topes de gasto.** `AI_DAILY_BUDGET_USD` (en `1` en el blueprint) corta la
IA para todos cuando se gasta eso en el día UTC, y el chat sigue con
botones. Además, cada local tiene su presupuesto mensual en la pantalla
**Asistente** (la demo arranca con US$ 5). Con un modelo chico, una
conversación de reserva cuesta centavos o menos.

---

## Variables de entorno, completas

### Render (backend)

| Variable | Obligatoria | Descripción |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sí | `demo`. **Es lo único que enciende el modo demo.** Sin esto, el seed y `/api/demo/reset` no existen |
| `SPRING_DATASOURCE_URL` | sí | JDBC de la conexión **directa** de Neon, con `?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | sí | `neondb_owner` |
| `SPRING_DATASOURCE_PASSWORD` | sí | Contraseña de ese rol. El backend no arranca sin esto |
| `JWT_SECRET` | sí | Clave HMAC para firmar los JWT, base64 de 32 bytes. La genera Render |
| `CORS_ALLOWED_ORIGINS` | sí, en la demo | Origen del frontend, sin barra final. Vacío = sin headers CORS (que es lo correcto cuando todo es mismo origen, como en Docker Compose) |
| `DEMO_ADMIN_EMAIL` | sí | Email del admin de demo que crea el seed |
| `DEMO_ADMIN_PASSWORD` | sí | Su contraseña. Es pública por diseño: va escrita en la pantalla de login |
| `DEMO_RESET_TOKEN` | sí | Token de `POST /api/demo/reset`. La genera Render. Vacío = el endpoint responde 404 |
| `DEMO_SEED_ON_STARTUP` | no (`true`) | `false` deja los datos como están al reiniciar, y el reseteo queda solo a mano |
| `DB_POOL_MAX` | no (`5`) | Tamaño máximo del pool Hikari |
| `PORT` | no | La inyecta Render sola |
| `JWT_EXPIRATION_SECONDS` | no (`86400`) | Vencimiento del token de sesión |
| `AI_SERVICE_URL` | no | URL pública de `turnos-ai`. Vacío = chat solo con botones |
| `AI_SERVICE_TOKEN` | con IA | Secreto compartido con `turnos-ai` |
| `AI_DAILY_BUDGET_USD` | no (`0`, sin tope) | Tope global diario de gasto en LLM |
| `FORWARD_HEADERS_STRATEGY` | sí, en Render | `framework`, para que el rate limit del chat vea la IP real |
| `TURNSTILE_SECRET` | no | Captcha en sesiones nuevas del chat |

### Render (turnos-ai)

| Variable | Obligatoria | Descripción |
|---|---|---|
| `ANTHROPIC_API_KEY` | sí | Clave del proveedor |
| `AI_SERVICE_TOKEN` | sí | El mismo secreto que en el backend. Vacío = rechaza todo |
| `CORE_BASE_URL` | sí | URL pública de `turnos-backend` |
| `AI_MODEL` | no (`claude-haiku-5-5`) | Modelo del agente |

### Vercel (frontend)

| Variable | Obligatoria | Descripción |
|---|---|---|
| `VITE_API_BASE_URL` | sí | URL completa de la API, terminada en `/api`. Sin esto el build apunta a `/api` del mismo dominio, donde no hay backend |
| `VITE_DEMO_EMAIL` | no | Email que muestra el cartel del login. Sin definir, no hay cartel |
| `VITE_DEMO_PASSWORD` | no | Contraseña que muestra ese cartel |
| `VITE_TURNSTILE_SITE_KEY` | no | Site key de Turnstile para el chat público |

---

## Regenerar los datos a mano

Si alguien dejó la demo hecha un desastre y no querés esperar al próximo
deploy:

```bash
curl -X POST https://turnos-backend-xxxx.onrender.com/api/demo/reset \
  -H "X-Demo-Reset-Token: EL_TOKEN_QUE_GENERO_RENDER"
# 204 No Content
```

Borra el tenant de demo entero y lo vuelve a crear con fechas nuevas. Es
idempotente: llamarlo diez veces seguidas deja lo mismo que llamarlo una.

---

## Por qué la conexión directa

La app resuelve el tenant de Row-Level Security con un GUC de **sesión**
(`TenantSessionContext` hace `set_config('app.tenant_id', ..., false)`), que
`TenantContextFilter` pone al empezar cada request y limpia al terminar.
Todo el request usa una única conexión física, que es justamente lo que
garantiza `hibernate.connection.handling_mode=IMMEDIATE_ACQUISITION_AND_HOLD`.

El pooler de Neon es PgBouncer en modo transacción: multiplexa varias
sesiones lógicas sobre pocas conexiones físicas y puede cambiarte de conexión
**entre statements**. Con eso, el `set_config` de un request puede perderse,
o peor, quedar puesto en una conexión que atiende a otro. El resultado no es
un error ruidoso: son filtros que devuelven de más o de menos. En este mismo
repo ya hubo un bug de esa familia (`91d197a`).

La solución de fondo sería `SET LOCAL` dentro de una transacción que abarque
todo el request, pero eso toca el filtro de tenant de toda la aplicación y es
un cambio para decidir aparte. **Mientras la demo use la conexión directa, el
modelo actual es correcto.** Si alguna vez migrás al pooler, hay que hacer
ese cambio primero.

---

## Cómo agregar un deploy de producción

No hay que tocar código. Producción es el mismo commit con otras variables:

1. **Base**: otro proyecto de Neon (o cualquier Postgres gestionado). Igual
   que acá, **conexión directa**, y con backups configurados — el plan free
   de Neon no es lugar para datos de un cliente real.
2. **Backend**: otro servicio en Render (o donde sea), apuntado al mismo
   repo. Las diferencias con la demo son:
   - **No definir `SPRING_PROFILES_ACTIVE=demo`.** Sin esa variable no se
     carga ninguna clase de seed ni el endpoint de reseteo, que directamente
     deja de existir. Hay un test que lo verifica (`DemoProfileAbsentTest`).
   - Omitir `DEMO_ADMIN_EMAIL`, `DEMO_ADMIN_PASSWORD` y `DEMO_RESET_TOKEN`:
     no los lee nadie.
   - `JWT_SECRET` **nuevo**, distinto del de la demo.
   - Agregar `SUPERADMIN_EMAIL` y `SUPERADMIN_PASSWORD` para sembrar tu
     cuenta de Super Admin y poder dar de alta el primer tenant real (ver
     README, "Alta del primer tenant").
   - Plan pago: el free duerme a los 15 minutos, lo que para un negocio que
     atiende turnos no sirve.
3. **Frontend**: otro proyecto de Vercel con su `VITE_API_BASE_URL`, y
   **sin** `VITE_DEMO_EMAIL` ni `VITE_DEMO_PASSWORD`, así no aparece el
   cartel de credenciales.
4. `CORS_ALLOWED_ORIGINS` del backend de producción apuntando al dominio de
   producción.

Lo que todavía no está resuelto para producción, y no lo resuelve esta guía:
backups y restore probados, dominio propio con HTTPS, y la elección del BSP
de WhatsApp (ver README, "Decisiones abiertas").
