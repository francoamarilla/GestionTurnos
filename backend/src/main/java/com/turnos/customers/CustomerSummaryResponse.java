package com.turnos.customers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One row of GET /api/customers/summary: the customer plus the numbers the
// clients table shows (ENDPOINTS-PENDIENTES.md #2), which used to cost one
// GET /api/customers/{id}/appointments per visible row. lastVisit is null
// for someone who has never completed an appointment, and is rendered in
// the tenant's timezone like every other time in the API.
public record CustomerSummaryResponse(
        UUID id,
        String phone,
        String name,
        boolean active,
        long visits,
        OffsetDateTime lastVisit,
        BigDecimal spent,
        long noShows) {
}
