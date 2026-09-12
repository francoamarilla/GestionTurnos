# Especificación técnica — SaaS de turnos con chatbot de WhatsApp

> Este documento es la **única fuente de verdad** para implementar este proyecto. Fue producido a lo largo de un proceso de diseño de 11 etapas entre Franco (dueño del producto) y un arquitecto de software. Cada decisión que aparece acá fue discutida, cuestionada y confirmada explícitamente — no hay nada puesto "por default" sin justificación.

## 0. Instrucciones para el agente/IA que va a programar esto

Leé esto antes de escribir una sola línea de código.

1. **No inventes nada que no esté en este documento.** Si una decisión de negocio, un campo, una regla o un endpoint no está especificado acá, no lo completes con un supuesto razonable propio — señalalo como pregunta abierta y esperá confirmación antes de implementarlo.
2. **No agregues funcionalidades fuera del alcance del MVP** (sección 12), aunque te parezcan "obvias" o "buenas prácticas". Hay una sección explícita de V2/Futuro justamente para dejar constancia de lo que se descartó a propósito — no lo reintroduzcas.
3. **No cambies el stack tecnológico** (sección 9) por otro que consideres mejor. Fue elegido con criterios específicos (experiencia previa de Franco, valor de portfolio, carga operativa de un desarrollador part-time). Si ves una razón técnica de peso para desviarte, preguntá primero.
4. **Las reglas de negocio de la sección 4 son no negociables** salvo indicación explícita de Franco. En particular: el no-solapamiento de turnos por profesional, el congelado de precio/duración al crear un turno, y el aislamiento estricto por tenant.
5. Donde el documento dice explícitamente "pendiente" o "sin decidir" (sección 13), no tomes una decisión vos mismo — es una decisión de producto que Franco todavía no cerró.
6. Los nombres de entidades, campos y endpoints están en **inglés** (decisión explícita), aunque el dominio de negocio se hable y describa en español en este documento.

---

## 1. Qué es el producto

Plataforma SaaS multi-tenant para negocios de turnos (peluquerías, barberías, manicura, centros de estética). El **MVP está acotado exclusivamente al rubro barbería** — el modelo de datos no impide extender a otros rubros después, pero no se construye nada específico para ellos ahora.

Dos canales de interacción:
- **Cliente final**: exclusivamente a través de un chatbot de WhatsApp. No existe ninguna aplicación ni login para el cliente final.
- **Establecimiento (dueño/administrador)**: a través de un dashboard web (SPA).

Cada establecimiento (barbería) es un **tenant** aislado: sus datos de clientes, turnos, profesionales y servicios nunca son visibles ni accesibles desde otro tenant, ni siquiera si comparten el mismo número de teléfono de cliente.

### Contexto del equipo (relevante para las decisiones de alcance)
Un solo desarrollador (Franco), part-time, junior. Esto justificó activamente decisiones de simplicidad a lo largo de todo el diseño (monolito en vez de microservicios, sin Redis, sin Kubernetes, roles colapsados, etc.) — no son atajos por descuido, son decisiones deliberadas dado este contexto.

---

## 2. Roles y permisos

Cuatro actores reales, no cinco (se descartó un rol "Manager" separado de "Owner" para el MVP — están fusionados):

| Rol | Alcance | Tiene login propio | Qué puede hacer |
|---|---|---|---|
| **Super Admin** | Toda la plataforma, ningún tenant específico | Sí | Alta/baja de establecimientos (tenants), ver su estado, soporte. Es Franco. No comparte tabla con `AdminUser` — es un concepto separado y liviano. |
| **Admin** (dueño del establecimiento, fusiona Owner+Manager) | Un único tenant | Sí | Todo dentro de su negocio: configuración, ABM de profesionales/servicios/clientes, agenda completa (alta manual de turnos, marcar completado/no-show), ventas, estadísticas. |
| **Profesional** (barbero) | Su propio perfil dentro de un tenant | **No** — es una entidad administrada por el Admin, no un usuario del sistema en el MVP | Nada directamente — el Admin ve y gestiona todo lo referido a él. |
| **Cliente final** | N/A — no es un usuario del sistema | No — se identifica por su número de WhatsApp | Interactúa solo vía chatbot: reservar, ver, cancelar o reprogramar sus propios turnos. |

**Regla de autorización clave**: un Admin solo puede leer/escribir datos de su propio tenant. El `tenantId` nunca viaja como parámetro manipulable por el cliente — siempre se deriva del lado del servidor, a partir del usuario autenticado (ver sección 10, autenticación).

---

## 3. Modelo de dominio

### 3.1 Entidades y atributos

**Tenant** (el establecimiento, a nivel de marca/negocio)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| businessName | String, requerido | |
| contactPhone | String, requerido | Teléfono de contacto general del negocio, no confundir con el número de WhatsApp del bot |
| status | enum: `ACTIVE`, `SUSPENDED`, `TRIAL` | |
| businessCategory | String | Hoy siempre `"barbershop"` — se guarda como dato para no hardcodear el vertical en el código |
| cancellationWindowHours | Integer | Ventana de anticipación para que el cliente cancele/reprograme. Default propuesto: `2`. Configurable por tenant. |
| timezone | String | Fijo: `"America/Argentina/Buenos_Aires"`. Se guarda pero no es editable desde la UI en el MVP. |
| createdAt | timestamp | |

**Branch** (Sucursal — modelada aparte del Tenant a propósito, aunque el MVP cree automáticamente una única sucursal por tenant y no tenga pantalla de gestión multi-sucursal)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| tenantId | UUID (FK → Tenant), requerido | |
| address | String, opcional | |
| — horario semanal — | ver `BranchSchedule` abajo | |

**BranchSchedule** (horario semanal de la sucursal — embebido/tabla hija, 7 filas por sucursal)
| Campo | Tipo | Notas |
|---|---|---|
| branchId | UUID (FK) | |
| dayOfWeek | enum (MONDAY..SUNDAY) | |
| openTime / closeTime | time, nullable | Nulo si `isClosed=true`. Admite rango partido mañana/tarde como dos filas del mismo día si hace falta, o un segundo par open/close — **definir el detalle exacto de rango partido en Etapa de implementación si Franco no lo precisó más**, pero el caso base (un rango por día) es obligatorio soportarlo. |
| isClosed | boolean | Día cerrado fijo (ej. lunes) |

**BranchClosure** (feriados/cierres puntuales — sub-recurso propio, se agrega/saca de a uno)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| branchId | UUID (FK) | |
| date | date | |
| reason | String, opcional | |

**AdminUser** (login del dueño del establecimiento)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| tenantId | UUID (FK → Tenant), **requerido, nunca nulo** | Ver nota de diseño abajo |
| email | String, único | |
| passwordHash | String | |
| role | enum: `ADMIN` | Campo ya preparado para un futuro valor `MANAGER` en V2, aunque hoy solo exista `ADMIN` |
| active | boolean | |

> **Nota de diseño explícita (no la cambies)**: el Super Admin **no** comparte esta tabla con un `tenantId` nulo para representar "es de la plataforma". Un campo opcional que cambia el significado completo de un registro según esté presente o no es una fuente típica de bugs de permisos. `SuperAdmin` es una entidad/tabla separada y liviana.

**SuperAdmin**
| Campo | Tipo |
|---|---|
| id | UUID (PK) |
| email | String, único |
| passwordHash | String |

**Professional** (Profesional/barbero — pertenece a una Sucursal)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| branchId | UUID (FK) | |
| name | String, requerido | |
| contactPhone | String, opcional | |
| active | boolean | Baja lógica — nunca se borra físicamente (rompería el historial de turnos) |
| — horario semanal propio — | ver `ProfessionalSchedule` | Mismo shape que `BranchSchedule`, puede ser subconjunto del horario del local |

**ProfessionalSchedule** — mismo shape que `BranchSchedule`, con `professionalId` en vez de `branchId`.

**Service** (Servicio — pertenece al Tenant, no a la Sucursal, porque el catálogo se comparte mientras haya una sola sucursal por tenant)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| tenantId | UUID (FK) | |
| name | String, requerido | |
| description | String, corta, opcional | Se muestra en el chat del bot |
| durationMinutes | Integer, **> 0 obligatorio** | Campo crítico: de acá sale todo el cálculo de disponibilidad. No es decorativo. |
| price | Decimal, >= 0 | |
| active | boolean | Baja lógica. Un servicio inactivo no puede reservarse por WhatsApp ni cargarse en un turno nuevo, pero los turnos pasados que lo usaron conservan la referencia sin problema. |

> **Combos** (ej. "Corte + Barba"): se modelan como un `Service` más, con su propio nombre/duración/precio. **No existe una entidad de "variación de servicio"** en el MVP — queda anotada para V2/Futuro si el catálogo real lo llegara a requerir.

**ProfessionalService** (tabla intermedia N a M)
| Campo | Tipo |
|---|---|
| professionalId | UUID (FK) |
| serviceId | UUID (FK) |

**Customer** (Cliente — pertenece al Tenant, identidad aislada por tenant)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| tenantId | UUID (FK) | |
| phone | String | **Obligatorio y única forma de identificación si el cliente viene del bot. Opcional si se crea desde el dashboard** (venta walk-in donde el admin decide no pedirlo). Único **por tenant**, no global — dos tenants distintos pueden tener cada uno un cliente con el mismo número. |
| name | String | Requerido si viene del bot (se pregunta en el primer contacto); opcional/libre desde el dashboard. |
| internalNotes | String, opcional | Preferencias, alergias a productos, etc. Cargado solo desde el dashboard. |
| active | boolean | Baja lógica. |

> Si el mismo cliente escribe alguna vez desde **otro número de teléfono**, en el MVP se trata como un cliente nuevo — no se intenta unificar automáticamente (queda anotado para V2).

**Appointment** (Turno — el corazón del dominio)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| branchId | UUID (FK), requerido | Un turno siempre pertenece a una sucursal, que pertenece a un tenant — nunca queda "suelto" sin esa cadena completa |
| professionalId | UUID (FK), requerido | |
| serviceId | UUID (FK), requerido | |
| customerId | UUID (FK), **nullable** | Nulo = walk-in anónimo. Válido, pero excluido de las métricas de clientes nuevos/recurrentes. |
| startTime | timestamptz | **Guardar siempre en UTC**, convertir a la timezone del tenant solo al calcular/mostrar |
| durationMinutes | Integer | **Copia congelada** de `Service.durationMinutes` al momento de crear el turno. No se recalcula si el servicio cambia después. |
| price | Decimal | **Copia congelada** de `Service.price` al momento de crear el turno (editable en el flujo de venta manual/walk-in, ej. por un descuento o extra). No cambia retroactivamente si el precio de catálogo cambia. |
| status | enum: `CONFIRMED`, `COMPLETED`, `CANCELLED`, `NO_SHOW` | Ver máquina de estados en 3.3. **No existe un estado "Reprogramado"** — reprogramar es cancelar + crear uno nuevo. |
| origin | enum: `BOT`, `DASHBOARD`, `DIRECT_SALE` | `DIRECT_SALE` = venta manual/walk-in (nace directo en `COMPLETED`) |
| rescheduledFromAppointmentId | UUID, nullable, auto-referencia a `Appointment.id` | Para trazar de qué turno nació una reprogramación |
| completedBy / noShowMarkedBy | UUID (FK → AdminUser), nullable | Quién marcó el turno como completado o no-show (mínimo de auditoría que decidimos sí vale la pena, sin construir una auditoría genérica completa) |
| completedAt | timestamp, nullable | |
| createdAt | timestamp | |

**ReminderSent** (RecordatorioEnviado — sostiene la idempotencia de los recordatorios)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| appointmentId | UUID (FK) | |
| type | enum: `CONFIRMATION`, `REMINDER_24H`, `REMINDER_2H`, `CANCELLATION` | |
| status | enum: `SENT`, `FAILED` | |
| attempts | Integer | |
| sentAt | timestamp, nullable | |

> Constraint de unicidad: `(appointmentId, type)` no puede tener más de un registro en estado `SENT` — es lo que garantiza que un mismo recordatorio nunca se manda dos veces.

**WhatsAppIntegration** (1:1 con Tenant, separada del Tenant porque tiene su propio ciclo de vida técnico)
| Campo | Tipo | Notas |
|---|---|---|
| id | UUID (PK) | |
| tenantId | UUID (FK), único | |
| wabaId | String | |
| phoneNumber | String | |
| verificationStatus | enum | Detalle exacto de valores **pendiente hasta elegir el proveedor BSP** — no inventar el shape final de este objeto, es lo único intencionalmente incompleto del modelo. |

**BotConversationState** (estado efímero de la conversación — no es fuente de verdad de nada de negocio)
| Campo | Tipo | Notas |
|---|---|---|
| tenantId + customerPhone | clave compuesta | |
| currentStep | String | En qué paso del flujo está |
| collectedData | JSON | Lo que fue eligiendo (servicio, profesional, horario tentativo) |
| expiresAt | timestamp | Se limpia con el mismo scheduler que corre los recordatorios (ver sección 6) |

### 3.2 Relaciones y cardinalidades

| Relación | Cardinalidad |
|---|---|
| Tenant → Branch | 1 a N (N=1 en el MVP, sin pantalla de gestión) |
| Tenant → AdminUser | 1 a N |
| Tenant → Customer | 1 a N |
| Tenant → Service | 1 a N |
| Tenant → WhatsAppIntegration | 1 a 1 |
| Branch → Professional | 1 a N |
| Professional ↔ Service | N a M (vía `ProfessionalService`) |
| Professional → Appointment | 1 a N |
| Service → Appointment | 1 a N |
| Customer → Appointment | 0..1 a N (opcional) |
| Appointment → ReminderSent | 1 a N |
| Appointment → Appointment | 0..1 a 0..1 (auto-referencia opcional, reprogramación) |

### 3.3 Máquina de estados de `Appointment`

```
                 ┌──────────────┐
   (creación) ──▶│  CONFIRMED   │
                 └──────┬───────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
    ┌───────────┐ ┌───────────┐ ┌───────────┐
    │ COMPLETED │ │ CANCELLED │ │  NO_SHOW  │
    └───────────┘ └───────────┘ └───────────┘
```

- Un turno con `origin = DIRECT_SALE` **nace directamente en `COMPLETED`**, sin pasar por `CONFIRMED`.
- No hay transición de `CANCELLED`, `COMPLETED` o `NO_SHOW` hacia ningún otro estado — son estados terminales.
- "Reprogramar" **no es una transición de este diagrama** — es una operación compuesta: cancelar el turno actual (`CONFIRMED → CANCELLED`) + crear un turno nuevo en `CONFIRMED`, enlazado vía `rescheduledFromAppointmentId`. Ver endpoint dedicado en sección 11.
- `COMPLETED` y `NO_SHOW` los marca manualmente el Admin — **no hay automatización que los dispare solos al pasar la hora del turno**, porque el precio final cobrado puede diferir del reservado y ese acto es también el momento donde se carga la venta real.

---

## 4. Reglas de negocio (no negociables salvo indicación explícita)

### Aislamiento multi-tenant
1. Todo dato pertenece a un único tenant. Ninguna operación puede leer o escribir datos de un tenant distinto al del usuario autenticado.
2. El teléfono de un `Customer` es único **por tenant**, nunca global. Un cliente identificado por WhatsApp en un establecimiento no tiene ninguna relación automática con su ficha en otro establecimiento, aunque comparta el mismo número.
3. El `tenantId` nunca se toma de un parámetro que envía el cliente/frontend — siempre se deriva del usuario autenticado en el backend.

### Turnos
4. **Un profesional no puede tener dos turnos superpuestos en el tiempo**, excluyendo del cálculo los turnos en estado `CANCELLED`. Esta es la regla de integridad más importante de todo el sistema, sin excepción — aplica sin importar el canal de origen (bot, dashboard, venta directa). Se refuerza a nivel de base de datos (constraint de exclusión de Postgres, ver sección 10), no solo en código de aplicación, porque el backend puede correr en más de una instancia.
5. Todo turno pertenece a una `Branch`, que pertenece a un `Tenant` — no existe un turno sin esa cadena completa.
6. Un turno solo puede crearse para un servicio que el profesional elegido efectivamente realiza (según `ProfessionalService`).
7. Un turno solo puede crearse dentro de la disponibilidad real del profesional (su horario semanal, menos bloqueos, menos turnos ya ocupados) y dentro del horario de atención de la sucursal.
8. La creación de un turno es **atómica**: se crea completamente confirmado o no se crea nada. Nunca queda en un estado intermedio.
9. Reprogramar es cancelar + crear nuevo — nunca se muta el horario de un turno existente.
10. Precio y duración quedan fijados (congelados) al momento de creación y no cambian retroactivamente si el servicio de catálogo cambia después.
11. Un turno `CANCELLED` o `NO_SHOW` **no genera venta**. Solo un turno `COMPLETED` genera venta.
12. El **cliente** puede cancelar/reprogramar un turno propio solo si falta más que `Tenant.cancellationWindowHours`. El **negocio** puede cancelar cualquier turno en cualquier momento, sin estar sujeto a esa misma restricción.
13. Un turno con `customerId` nulo (walk-in anónimo) es válido, pero queda excluido de las métricas de clientes nuevos/recurrentes.

### Profesionales y servicios
14. Un profesional inactivo no puede recibir turnos nuevos, pero conserva su historial de turnos pasados.
15. **No se puede dar de baja a un profesional si tiene turnos futuros en estado `CONFIRMED` sin resolver** — la baja se bloquea (409) hasta que el Admin los cancele o reasigne primero.
16. Un servicio inactivo no puede reservarse por WhatsApp ni cargarse en un turno nuevo, pero los turnos pasados que lo usaron mantienen su referencia.
17. Un servicio debe tener `durationMinutes > 0`.
18. Nunca se puede asignar un turno de un servicio a un profesional no habilitado para realizarlo (según `ProfessionalService`), sea por WhatsApp o por carga manual.

### Clientes
19. Un cliente creado por el bot siempre tiene teléfono; uno creado desde el dashboard puede no tenerlo.
20. Un cliente inactivo no puede recibir turnos nuevos asignados a su ficha, pero conserva su historial.
21. **Baja lógica transversal**: dar de baja a un `Customer` o `Professional` nunca borra físicamente el registro — siempre `active = false`, para no romper la integridad del historial de turnos que los referencian. Lo mismo aplica a `Service`.

### Recordatorios y notificaciones
22. Un recordatorio programado solo se envía a turnos que siguen en estado `CONFIRMED` en el momento exacto en que se ejecuta el job. Si el turno cambió de estado antes, no se envía nada (no hace falta cancelar nada explícitamente).
23. Un mismo recordatorio (combinación turno + tipo) no puede enviarse dos veces (ver constraint de unicidad en `ReminderSent`).
24. Todo mensaje fuera de la ventana de servicio de WhatsApp de 24hs (CSW) tiene que salir como plantilla aprobada por Meta — nunca como mensaje libre. **Para el MVP, todo recordatorio programado automáticamente sale siempre como plantilla**, sin lógica condicional de "a veces hay ventana abierta, a veces no" (más simple y predecible).
25. Si los reintentos de un recordatorio se agotan sin éxito, se marca `FAILED` y queda visible como alerta en el dashboard del Admin — nunca falla en silencio.
26. Todo el cálculo de "faltan N horas" se hace en la timezone del tenant (`America/Argentina/Buenos_Aires`). Los horarios se guardan siempre en UTC en la base de datos.

### Ventas y estadísticas
27. Una venta siempre está asociada a un turno `COMPLETED`; no existe una venta sin turno subyacente, ni siquiera para walk-ins (que también pasan por un turno, uno que nace ya completado).
28. Tasa de cancelación y tasa de no-show se calculan sobre la **fecha del turno**, no sobre la fecha en que ocurrió el evento de cancelación/no-show.

---

## 5. Chatbot de WhatsApp — flujos conversacionales

**Motor: determinístico por botones/listas interactivas de WhatsApp.** Nada de NLU/LLM ni texto libre interpretado en el MVP.

### Menú raíz
Primer contacto (o cualquier mensaje fuera de un flujo activo) siempre muestra:
> "¡Hola! 👋 Soy el asistente de [Barbería]. ¿Qué querés hacer?"
> Opciones: *Reservar un turno* / *Ver mis turnos* / *Cancelar o modificar un turno* / *Hablar con alguien del local*

### Flujo: reservar un turno
1. Lista de servicios activos (`Service.active = true`) con precio y duración.
2. Con servicio elegido: lista de profesionales habilitados para ese servicio (`ProfessionalService`), **siempre con la opción "Cualquiera disponible"**.
3. Con profesional (o "cualquiera"): el bot consulta disponibilidad real (endpoint/servicio de disponibilidad, ver sección 11) y muestra franjas horarias concretas de los próximos días.
4. El cliente elige una franja → el bot pide **confirmación explícita** mostrando servicio, profesional, fecha/hora y precio.
5. Al confirmar: se intenta crear el turno con la validación de no-solapamiento aplicada **en la misma operación/transacción**. Si en el ínterin otro cliente tomó ese horario, el bot lo informa y vuelve a ofrecer horarios actualizados — nunca falla en silencio ni duplica.

### Flujo: ver / cancelar / modificar turnos propios
1. El bot identifica al cliente por su número de teléfono (dentro del tenant del número de WhatsApp de negocio al que escribió) y busca sus turnos futuros en `CONFIRMED`.
2. Si no tiene ninguno, lo invita a reservar.
3. Si tiene uno o más, los lista y pregunta qué hacer con cada uno.
4. **Cancelar**: valida `Tenant.cancellationWindowHours`. Si está dentro del margen permitido, cancela y confirma. Si está fuera de margen, no lo bloquea secamente — explica la política y ofrece derivarlo con el local (opción "hablar con alguien").
5. **Modificar/reprogramar**: repite el flujo de elegir nueva franja (mismo servicio/profesional por defecto, con opción de cambiar). Al confirmar: cierra el turno anterior como `CANCELLED` y crea uno nuevo `CONFIRMED`, enlazados vía `rescheduledFromAppointmentId`.

### Manejo de texto inesperado
Si el cliente escribe algo que no matchea ninguna opción del paso actual: el bot responde que no entendió y **vuelve a mostrar las opciones válidas de ese paso puntual** (nunca reinicia toda la conversación). Si se repite 2-3 veces seguidas en el mismo paso, ofrece directamente la opción de hablar con una persona.

### Sin disponibilidad
Si no hay ningún horario libre en el rango mostrado: informarlo con claridad y ofrecer alternativas (ampliar el rango de días, o probar "cualquier profesional" si había uno específico elegido). **No hay lista de espera (waitlist) en el MVP.**

### Timeout de sesión conversacional
~30 minutos de inactividad. Pasado ese tiempo, el estado (`BotConversationState`) se descarta y el próximo mensaje del cliente arranca desde el menú raíz, no desde donde había quedado.

### Identificación del cliente
El número de WhatsApp de origen es el identificador. Primera vez que escribe un número nuevo → el bot pide el nombre antes de avanzar a reservar. Búsqueda de cliente siempre acotada al tenant del número de negocio al que escribió.

### Concurrencia — cómo se evitan reservas duplicadas
- Mismo cliente con turno casi duplicado: se le avisa (no se bloquea del todo) si ya tiene un turno el mismo día antes de dejarlo confirmar uno nuevo.
- Condición de carrera real (dos clientes disputando el mismo horario del mismo profesional): se resuelve **con una restricción a nivel de base de datos** (constraint de exclusión, verificado dentro de la misma transacción que crea el turno) — nunca solo "revisando antes de guardar" en código de aplicación, porque el backend podría correr en más de una instancia.

### Derivar a una persona
La opción "Hablar con alguien del local" está siempre visible, nunca escondida en un submenú. Al activarse, el bot deja de responder automáticamente en esa conversación puntual.

### Separación de responsabilidades bot/backend
El bot solo guarda el estado mínimo y efímero de la conversación en curso (`BotConversationState`). No es fuente de verdad de nada. El backend es dueño de todo lo que persiste. El bot **nunca decide por sí mismo** que hay un horario libre — siempre se lo pregunta al backend en el momento exacto en que lo necesita.

---

## 6. Recordatorios y automatizaciones

- Se modelan como **una sola pieza reutilizable**: una regla de recordatorio con un offset configurable respecto a la hora del turno. Por defecto, dos reglas activas: **24hs antes** y **2hs antes**.
- Mecanismo: **job periódico de barrido**, no un job individual programado por turno. Intervalo recomendado: cada 10-15 minutos. En cada ejecución, consulta "¿qué turnos siguen `CONFIRMED` y su horario cae dentro de la ventana de 24hs/2hs desde ahora, y todavía no recibieron ese recordatorio?" y envía a los que encuentra. Si un turno fue cancelado/reprogramado antes, simplemente ya no aparece en el resultado — no hace falta cancelar nada explícito.
- **Idempotencia**: cada envío exitoso deja una marca en `ReminderSent` (ver constraint de unicidad en sección 3.1) en la misma operación que confirma el envío.
- **Reintentos**: backoff hasta un máximo de intentos; agotados, se marca `FAILED` y queda visible en el dashboard.
- La confirmación inmediata al reservar normalmente sale dentro de la ventana de servicio (CSW) de WhatsApp, como mensaje libre. Los recordatorios 24h/2h y cualquier notificación de cambio de estado originada por el negocio (fuera del chat activo) salen siempre como **plantilla aprobada**.
- Mensajes post-turno y campañas de marketing (cumpleaños, reactivación) están **explícitamente fuera del MVP** — caen en categoría *Marketing* de Meta, exigen opt-in, y no aportan al problema central.
- El mismo job/scheduler de recordatorios se reutiliza para limpiar `BotConversationState` expirado (no se crea un scheduler separado para eso).

---

## 7. Dashboard — pantallas y estadísticas

### Pantallas del Admin (navegación plana, menú lateral)
1. **Inicio**: turnos de hoy, próximos turnos, cancelaciones recientes, resumen de ventas del día/semana, **alerta visible de recordatorios que fallaron al enviarse**.
2. **Agenda**: vista de calendario (profesionales como columnas, horario como filas). Acá se carga un turno manual y se marca `COMPLETED`/`NO_SHOW`.
3. **Clientes**: listado con búsqueda + ficha con historial de turnos.
4. **Profesionales**: ABM, servicios que realiza cada uno, horario semanal.
5. **Servicios**: catálogo (nombre, duración, precio).
6. **Ventas**: turnos `COMPLETED`, filtrable por período y por profesional. Incluye el flujo de **venta manual/walk-in** (ver más abajo).
7. **Estadísticas**: los seis reportes definidos abajo.
8. **Configuración**: datos del establecimiento, horario general, feriados, ventana de cancelación, estado de integración de WhatsApp.

### Super Admin
Pantalla mínima: lista de establecimientos con su estado (activo/suspendido/en prueba) y si su WhatsApp está verificado. **No se invierte en una UI pulida para este rol en el MVP.**

### Venta manual / walk-in
El Admin elige: servicio del catálogo, profesional que lo atendió, fecha/hora (default "ahora", editable), precio (editable, por si hubo descuento/extra), y **opcionalmente** nombre + teléfono del cliente (el Admin decide libremente si los carga o no). Al confirmar: se crea un `Appointment` con `origin = DIRECT_SALE`, `status = COMPLETED` directamente, aplicando igual la validación de no-solapamiento (regla 4, sin excepciones).

### Definición exacta de las estadísticas (sin ambigüedad)
| Métrica | Fórmula exacta |
|---|---|
| Ventas por período / por profesional | Suma de `price` de `Appointment` con `status = COMPLETED`, agrupado por fecha o por `professionalId` |
| Servicios más realizados | Conteo de `Appointment` con `status = COMPLETED`, agrupado por `serviceId` |
| Clientes nuevos | Clientes cuyo **primer** turno `COMPLETED` cae dentro del período consultado |
| Clientes recurrentes | Clientes que ya tenían **al menos un** turno `COMPLETED` **antes** del inicio del período consultado |
| Tasa de cancelación | `CANCELLED` cuyo turno (`startTime`) cae en el período, dividido por `(COMPLETED + CANCELLED + NO_SHOW)` en ese mismo período. Usa la fecha del turno, no la fecha del evento de cancelación. |
| Tasa de no-show | `NO_SHOW` dividido por `(COMPLETED + NO_SHOW)` en el período — **no incluye `CANCELLED` en el denominador** |
| Ocupación de agenda | Horas de turnos reservados dividido por horas de disponibilidad real del profesional en el período (su horario configurado menos bloqueos) |

> **"Cantidad de turnos" fue removida explícitamente del MVP** (decisión de Franco) — no agregarla ni como tarjeta ni como endpoint. El desglose por estado (completados/cancelados/no-shows) ya está disponible vía las otras métricas.

---

## 8. Arquitectura

- **Monolito modular** — microservicios explícitamente descartados. Módulos internos por dominio: turnos, clientes, profesionales, servicios, chatbot, notificaciones, ventas.
- El bot de WhatsApp **no accede directo a la base de datos** — pasa por los mismos servicios de dominio internos que expone la API REST, para no duplicar reglas de negocio entre canales.
- **Multi-tenancy**: fila compartida (una sola base, un solo esquema), con `tenant_id` como columna discriminadora (directa o vía `Branch`) en cada tabla relevante, reforzado con **Row-Level Security (RLS) nativo de Postgres** como segunda capa de seguridad además del filtrado en código de aplicación. Explícitamente descartados: esquema separado por tenant y base de datos separada por tenant.
- **Webhook de WhatsApp único** para todos los tenants — identifica a qué tenant pertenece cada mensaje por el número de teléfono de **destino**.
- **Jobs programados** (recordatorios, limpieza de `BotConversationState`) corren dentro del mismo proceso backend — no hay sistema de colas separado.
- **Sin cache dedicado** (Redis) en el MVP — el estado de conversación del bot vive en una tabla SQL con expiración + job de limpieza.
- **Logs estructurados** desde el día uno, incluyendo siempre `tenantId` en cada entrada relevante. **Sin auditoría genérica** de cambios en el MVP — el mínimo de trazabilidad que sí se implementa es `Appointment.completedBy/noShowMarkedBy` + el historial completo de `ReminderSent`.
- **Manejo de errores**: formato de error consistente en toda la API (ver sección 11). El conflicto de solapamiento de turno debe devolverse como una respuesta clara y específica ("ese horario ya no está disponible"), nunca un error genérico. Un fallo de envío de WhatsApp **nunca revierte un turno ya creado** — el turno queda creado, el mensaje se reintenta por separado.
- **Observabilidad**: logs estructurados + healthcheck básico (proceso arriba, base responde, job de recordatorios corriendo). Sin tracing distribuido.

---

## 9. Stack tecnológico (definitivo)

| Capa | Elección | Notas |
|---|---|---|
| Backend | **Java 21 + Spring Boot 3+** | Spring Data JPA, Spring Security, Jakarta Validation |
| Persistencia | **PostgreSQL** | Se usa la extensión `btree_gist` para la restricción de exclusión de no-solapamiento |
| Migraciones | **Flyway** | SQL plano versionado — necesario para escribir a mano la restricción de exclusión, no expresable con anotaciones JPA |
| Frontend | **React** | + **React Router** (ruteo) + **React Hook Form** (formularios de alta/edición). Cliente HTTP (fetch nativo vs. axios) sin decidir — decisión menor a tomar en implementación. |
| Testing | **JUnit 5 + Mockito + JaCoCo** | Prioridad de cobertura: reglas de negocio (solapamiento, disponibilidad, cancelación) antes que controladores/e2e |
| API | **REST + OpenAPI/Swagger** | vía `springdoc-openapi` |
| Autenticación | **JWT** | Sin sesión de servidor. Expiración ~24hs, sin refresh token sofisticado en el MVP |
| Infraestructura | **Docker + Docker Compose** | Backend + Postgres. **Sin Kubernetes.** Hosting sugerido: Railway/Render o VPS chico |
| Estado del bot | Tabla SQL + columna de expiración + job de limpieza | No Redis |
| Proveedor WhatsApp (BSP) | **Sin definir** | Franco lo evalúa por separado. No asumir un proveedor concreto en el código — abstraer detrás de una interfaz/puerto para poder conectarlo sin reescribir el resto. |

---

## 10. Autenticación y autorización

- Único endpoint de login (`POST /api/auth/login`) para `Admin` y `SuperAdmin`, diferenciados por un claim `role` dentro del JWT.
- El JWT de un Admin lleva además `tenantId`. El de SuperAdmin no.
- Un filtro/interceptor toma el `tenantId` del JWT en cada request autenticado y lo aplica automáticamente (contexto de conexión a Postgres para que RLS lo use, y/o filtro explícito en cada consulta) — ninguna consulta puede "olvidarse" de filtrar por tenant.
- El cliente final (WhatsApp) no tiene contraseña ni JWT — su identidad ya la verificó Meta al entregar el mensaje desde ese número de origen.

---

## 11. API y contratos

### Convenciones generales
- Recursos y endpoints en **inglés**. JSON en **camelCase**.
- **Sin prefijo de versión** (`/api/...` a secas) — un solo cliente (la propia SPA) por ahora.
- **Paginación**: offset-based (`page`, `size`), vía `Pageable` de Spring Data.
- **Errores**: formato **RFC 7807** (`application/problem+json`) — `type`, `title`, `status`, `detail`, y `errors` para validaciones por campo.
- **Códigos de estado**:
  - `400` — validación de input (campo faltante, formato inválido).
  - `401` — no autenticado / token inválido o vencido.
  - `403` — autenticado pero sin permiso (rol equivocado, o intenta tocar un recurso de otro tenant).
  - `404` — no encontrado, **o pertenece a otro tenant** (mismo código en ambos casos, para no filtrar existencia cross-tenant).
  - `409` — violación de regla de negocio dependiente del estado (solapamiento, baja bloqueada, transición de estado inválida).

### Endpoints

**Auth**
- `POST /api/auth/login` — `{email, password}` → `{accessToken, expiresIn, role, tenantId?, establishmentName?}`. 401 si las credenciales no son válidas.

**Tenants** (Super Admin — alta manual, sin autoregistro público en el MVP)
- `GET /api/admin/tenants`
- `POST /api/admin/tenants`
- `GET /api/admin/tenants/{id}`
- `PATCH /api/admin/tenants/{id}`

**Establishment** (self-service, Admin — implícito por JWT, sin ID en la URL)
- `GET /api/establishment`
- `PATCH /api/establishment` — nombre, contacto, horario semanal (embebido)
- `GET / POST / DELETE /api/establishment/closures` — feriados/cierres puntuales
- `GET /api/establishment/whatsapp-integration` — contrato de *alta* pendiente del BSP

**Professionals**
- `GET /api/professionals` (filtro `active`, paginado)
- `POST /api/professionals`
- `GET / PATCH /api/professionals/{id}`
- `DELETE /api/professionals/{id}` — baja lógica. **409** si tiene turnos futuros `CONFIRMED` sin resolver.
- `PUT /api/professionals/{id}/services` — reemplaza el conjunto completo de servicios que ofrece
- `GET / PATCH /api/professionals/{id}/schedule`

**Services**
- `GET /api/services`
- `POST /api/services`
- `GET / PATCH /api/services/{id}`
- `DELETE /api/services/{id}` — baja lógica

**Customers**
- `GET /api/customers` (búsqueda por nombre/teléfono, paginado)
- `POST /api/customers`
- `GET / PATCH /api/customers/{id}`
- `DELETE /api/customers/{id}` — baja lógica
- `GET /api/customers/{id}/appointments`

**Appointments**
- `GET /api/appointments?from=&to=&professionalId=&status=` — `from`/`to` **obligatorios**
- `POST /api/appointments` — alta manual desde el dashboard (el bot crea in-process, sin pasar por HTTP)
- `GET /api/appointments/{id}`
- `PATCH /api/appointments/{id}/status` — transición explícita (`COMPLETED`, `CANCELLED`, `NO_SHOW`). **409** si la transición no es válida.
- `POST /api/appointments/{id}/reschedule` — encapsula cancelar+crear en una sola llamada atómica
- `GET /api/availability?professionalId=&serviceId=&date=` — usado por el dashboard; el bot reutiliza la lógica de dominio directo (in-process)

**Sales** (endpoint propio, no reutiliza `/appointments`)
- `GET /api/sales?from=&to=&professionalId=`
- `POST /api/sales` — venta manual/walk-in. Internamente crea un `Appointment` con `origin=DIRECT_SALE`, `status=COMPLETED`.

**Statistics**
- `GET /api/statistics/summary?from=&to=` — un solo endpoint agregador con las 6 métricas de la sección 7
- `GET /api/admin/statistics` — mínimo para Super Admin

**WhatsApp webhook**
- `POST /api/webhooks/whatsapp` — único endpoint fuera de las convenciones REST de arriba: público, autenticado por firma del proveedor (no JWT), responde rápido (~200 inmediato) y procesa de forma asíncrona. **Shape exacto del payload pendiente hasta elegir el BSP** — no inventarlo, dejar la interfaz de entrada abstraída.

---

## 12. Alcance MVP (todo lo de arriba) — checklist de construcción

Todo lo descrito en las secciones 1 a 11 es el **MVP completo**. En síntesis, para que quede como checklist:

- [ ] Auth JWT (Super Admin + Admin)
- [ ] Alta manual de tenants (Super Admin)
- [ ] Configuración de establecimiento: horario semanal, feriados, ventana de cancelación
- [ ] CRUD Profesionales (+ servicios que ofrece, horario propio, baja lógica bloqueada por turnos pendientes)
- [ ] CRUD Servicios (baja lógica)
- [ ] CRUD Clientes (aislados por tenant, baja lógica)
- [ ] Turnos: máquina de estados completa, no-solapamiento (constraint de exclusión Postgres), ventana de anticipación, reprogramar=cancelar+crear, precio/duración congelados
- [ ] Chatbot determinístico por botones/listas: reservar, ver/cancelar/modificar, timeout de sesión, escalar a humano
- [ ] Recordatorios automáticos 24h/2h vía plantilla, job de barrido, idempotencia, reintentos con backoff
- [ ] Venta manual/walk-in
- [ ] Dashboard completo (8 pantallas de la sección 7) + estadísticas (6 métricas con fórmula exacta)
- [ ] Multi-tenancy con RLS + tenant_id
- [ ] Docker Compose (backend + Postgres)

---

## 13. Decisiones explícitamente NO tomadas (no completar por tu cuenta)

- **Proveedor de WhatsApp Business API (BSP)** concreto — Franco lo evalúa por separado. Candidatos investigados como referencia: 360dialog, Twilio, Gupshup — pero **no está confirmado ninguno**. El código debe abstraer la integración de WhatsApp detrás de una interfaz para no acoplarse a un proveedor específico.
- **Ventana exacta de anticipación para cancelar/modificar un turno** — el valor `2` horas es un default propuesto, no confirmado como definitivo. Debe ser configurable por tenant (`Tenant.cancellationWindowHours`), no hardcodeado.
- **Tesis de diferenciación** frente a competidores (AgendaPro, Fresha, Booksy, etc.) — no afecta directamente al código, pero puede afectar prioridades futuras.
- **Modelo de negocio / pricing** de la plataforma — no definido, no relevante para el código del MVP.
- **Cliente HTTP del frontend** (fetch nativo vs. axios) — decisión menor, libre de elegir en implementación.
- **Rango horario partido (mañana/tarde) en `BranchSchedule`/`ProfessionalSchedule`** — el caso base (un rango por día) es obligatorio; el detalle de implementación de un rango partido no fue especificado con precisión y puede requerir confirmación antes de implementarse si aparece como necesidad real.

---

## 14. Fuera de alcance — V2 (próxima iteración post-MVP)

No implementar en el MVP. Anotado para no perderse, no para adelantarse:
- Rol `Manager` separado de `Admin`, con permisos recortados.
- Login/dashboard propio para el Profesional.
- Integración de calendario oficial de feriados de Argentina.
- Unificación automática de clientes que escriben desde un número distinto.
- Herramienta de reprogramación masiva (cuando un profesional falta un día completo).
- Cobro de seña/depósito online para reducir no-shows.
- Entidad de "variación de servicio" si el catálogo lo llegara a requerir.
- Lista de espera (waitlist) cuando no hay disponibilidad.
- Mensajes post-turno y campañas de marketing (cumpleaños, reactivación de clientes).
- Panel de Super Admin con UI completa.
- Refresh token / rotación de sesión más sofisticada que un JWT simple.

## 15. Fuera de alcance — Futuro (especulativo, depende de tracción)

- Expansión a otros rubros (peluquería, manicura, estética).
- Multi-sucursal real con UI de gestión (hoy solo soportado a nivel de modelo de datos).
- Motor de chatbot con NLU/LLM en texto libre.
- App nativa / PWA instalable.
- Facturación fiscal (AFIP).
- Punto de venta genérico (productos/inventario) más allá de los servicios del catálogo.
- Auditoría genérica completa de cambios.
- Cache dedicado (Redis) más allá del estado de conversación del bot.
- Analítica de plataforma para Super Admin (churn, MRR, etc.).
- Internacionalización (otros países/zonas horarias).
- Modelo de marca blanca / reventa a otros desarrolladores.
