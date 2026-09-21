package com.turnos.sales;

import com.turnos.appointments.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

// Corrects what gets entered wrong at the till (ENDPOINTS-PENDIENTES.md
// #10): price, payment method and customer. Not a partial patch - the
// dialog always sends all three, so null means null (customer null = walk-in,
// paymentMethod null = not recorded). The customer fields work like
// CreateSaleRequest's. Service, professional or time are fixed: void the
// sale and register it again.
public record UpdateSaleRequest(
        @NotNull @DecimalMin("0") BigDecimal price,
        PaymentMethod paymentMethod,
        UUID customerId,
        String customerName,
        String customerPhone) {
}
