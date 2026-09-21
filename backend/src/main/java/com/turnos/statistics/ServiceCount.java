package com.turnos.statistics;

import java.util.UUID;

// serviceId null = "Otro": sales off the catalog, all in one row.
public record ServiceCount(UUID serviceId, long count) {
}
