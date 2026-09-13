package com.turnos.statistics;

import java.util.UUID;

public record ServiceCount(UUID serviceId, long count) {
}
