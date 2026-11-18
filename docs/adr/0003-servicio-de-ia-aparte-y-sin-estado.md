# ADR-0003: Servicio de IA aparte, en Python, sin estado y sin acceso a la base

**Estado:** aceptada · noviembre 2026

## Decisión

- `ai-service` (FastAPI) recibe en cada turno todo lo que necesita: mensaje, historial, configuración del dueño, borrador pendiente y token. No guarda nada: se puede reiniciar o escalar sin perder conversaciones.
- No se conecta a Postgres. Toda lectura y escritura pasa por la API interna de Spring. Las políticas RLS dependen de un `set_config` de sesión que hace Spring, y un segundo cliente de la base las rompería.
- Loop de agente propio (`app/agent.py`) más Pydantic, sin frameworks: lecturas en paralelo, escrituras en serie, tope de 6 iteraciones. Un error de una herramienta vuelve al modelo como resultado.
- Una capa mínima de proveedor (`LLMProvider`): hoy un solo adaptador (Anthropic). Los evals comparan modelos cambiando `AI_MODEL`.

## Consecuencias

- Dos despliegues en lugar de uno (en Render, dos web services). ai-service queda público y se protege con `AI_SERVICE_TOKEN`.
- Mientras el agente piensa, el request del chat mantiene su conexión del pool. Cada chat en curso usa 2 conexiones, y eso hay que mirarlo si el pool queda corto.
