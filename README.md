# Turnos SaaS — Barbería con chatbot de WhatsApp

Plataforma SaaS multi-tenant de turnos para barberías, con dashboard web para el administrador y un chatbot de WhatsApp para el cliente final.

La especificación completa y vinculante del producto está en [`ESPECIFICACION-TECNICA.md`](./ESPECIFICACION-TECNICA.md). Ese documento es la única fuente de verdad — cualquier duda de comportamiento se resuelve ahí, no acá.

## Estructura del repo

```
backend/     Java 21 + Spring Boot 3 (API REST, dominio, bot in-process)
frontend/    React (dashboard SPA)
docker-compose.yml
```

## Stack

Ver sección 9 de la especificación técnica para el detalle completo y las razones de cada elección.

## Cómo levantar el proyecto

_Se documenta a medida que el backend y el frontend queden scaffoldeados (ver plan de commits)._
