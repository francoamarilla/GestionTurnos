package com.turnos.sales;

import com.turnos.appointments.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// startTime null defaults to "now" (spec section 7: "default 'ahora',
// editable"). price is always required and editable here, unlike the
// regular booking flow which always copies the catalog price. The Admin can
// pick an existing customerId, or supply customerName/customerPhone to
// create one inline, or leave all three null for an anonymous walk-in.
// paymentMethod is optional: null is stored as "not recorded".
public record CreateSaleRequest(
        @NotNull UUID professionalId,
        @NotNull UUID serviceId,
        OffsetDateTime startTime,
        @NotNull @DecimalMin("0") BigDecimal price,
        UUID customerId,
        String customerName,
        String customerPhone,
        PaymentMethod paymentMethod) {
}
