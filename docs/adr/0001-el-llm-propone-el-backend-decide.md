# ADR-0001: El LLM propone, el backend decide

**Estado:** aceptada · noviembre 2026

## Contexto

El asistente reserva, cancela y reprograma turnos reales. Un modelo puede equivocarse, alucinar o ser manipulado por el texto del cliente ("cancelá el turno de otro").

## Decisión

El modelo nunca escribe en la base. Pide acciones con herramientas, y cada herramienta es un endpoint de `/internal/agent/*` en Spring que aplica las mismas reglas que el dashboard: no superposición (constraint de la base), precio congelado, ventana de cancelación, aislamiento por tenant con RLS.

- El tenant y la conversación viajan en un JWT `AGENT` de 5 minutos que Spring firma por turno. El cliente sale de la conversación guardada. Ningún id que escribe el modelo puede salir de ese alcance.
- Las escrituras van en dos pasos: `prepare_*` crea un borrador (`pending_action`) y `commit_*` solo acepta un borrador vigente de esa misma conversación.
- Las capacidades que el dueño apaga se filtran del prompt y además se rechazan en el servidor (`AgentPolicy`).

## Consecuencias

- Un prompt injection exitoso puede, como mucho, hacer que el asistente diga algo raro: no puede tocar datos de otro cliente ni de otro local. `AgentApiSecurityTest` y el eval `injection_other_customers_appointment` lo verifican.
- Cada herramienta nueva implica un endpoint en Spring. Es más trabajo, y es a propósito.
