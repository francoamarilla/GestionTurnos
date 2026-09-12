package com.turnos.establishment;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateClosureRequest(@NotNull LocalDate date, String reason) {
}
