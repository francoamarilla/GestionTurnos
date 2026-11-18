# ADR-0004: El bot de botones queda como fallback del asistente

**Estado:** aceptada · noviembre 2026

## Decisión

Spring decide en cada mensaje quién responde (`ConversationService`):

| Situación | Responde |
|---|---|
| Conversación derivada a una persona (`HUMAN`) | Nadie: aviso fijo hasta que el dueño la devuelve |
| Toque de botón, o el cliente escribe "menú" | Bot de botones |
| IA apagada por el dueño, sin presupuesto del mes, o tope diario global | Bot de botones |
| El agente falla o no logra responder | Bot de botones, en ese mismo mensaje |
| Resto | Agente de IA |

El bot es la misma máquina de estados que usa WhatsApp (`BotConversationService`). Para la web, sus envíos se capturan y vuelven en la respuesta HTTP como texto más botones, en lugar de salir por `WhatsAppProvider`. Esa captura es todo el adaptador de canal. Como el bot identifica por teléfono, en la web lo pide primero.

## Consecuencias

- Un corte del proveedor del LLM o un presupuesto agotado no dejan al local sin atención.
- Los mensajes del bot se guardan en el historial: cuando vuelve la IA, ve lo que se hizo con botones.
