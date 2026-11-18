# ADR-0002: La precisión del pedido la mide el código, no el modelo

**Estado:** aceptada · noviembre 2026

## Contexto

Criterio de producto (D7): si el cliente fue preciso, se reserva directo; si no, se pide confirmación. Los modelos no evalúan bien su propia certeza, y es justo el tipo de decisión que se rompe sin hacer ruido.

## Decisión

El modelo le pasa a `prepare_booking` las **palabras literales** del cliente (`"corte"`, `"Juan"`, `"el jueves"`, `"tipo 10"`). `BookingDraftResolver` (Java, función pura) las resuelve con reglas explícitas:

- **Bloqueos** (no hay borrador; el modelo pregunta o busca): servicio inexistente o ambiguo, franja horaria ("a la tarde"), fecha vaga ("la semana que viene").
- **Ambigüedades** (hay borrador, pero en modo CONFIRM): profesional no dicho con varios disponibles, "el jueves" dicho un jueves, hora aproximada ("tipo 10"), "a las 6" leído como 18 h, posible duplicado del mismo día.
- El modo final lo decide `confirmation_mode` del dueño: `STANDARD`, `DATE_TIME_ONLY` o `ALWAYS`.

Doble cinturón: un borrador CONFIRM solo se confirma si existe un mensaje del cliente **posterior** al borrador. El modelo no puede fabricar ese mensaje.

## Consecuencias

- Cada fila de la tabla tiene su caso en `BookingDraftResolverTest` y escenarios en los evals.
- Las cancelaciones se confirman siempre (A2): una cancelación equivocada se descubre recién cuando el cliente llega al local.
- El fake de los evals replica estas reglas en Python. Si cambia el resolver, hay que actualizar `evals/fake_core.py`.
