package com.turnos.sales;

import jakarta.validation.constraints.Size;

// reason is optional (user decision, #10).
public record VoidSaleRequest(@Size(max = 200) String reason) {
}
