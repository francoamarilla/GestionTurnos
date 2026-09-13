package com.turnos.statistics;

import java.math.BigDecimal;
import java.util.UUID;

public record ProfessionalAmount(UUID professionalId, BigDecimal total) {
}
