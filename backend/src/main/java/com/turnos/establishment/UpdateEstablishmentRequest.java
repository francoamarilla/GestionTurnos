package com.turnos.establishment;

import java.util.List;

// Partial update: only non-null fields are applied. Sending schedule
// replaces the full 7-day week in one shot (spec section 11).
public record UpdateEstablishmentRequest(
        String businessName,
        String contactPhone,
        Integer cancellationWindowHours,
        List<DaySchedule> schedule) {
}
