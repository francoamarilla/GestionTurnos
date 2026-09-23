package com.turnos.establishment;

import jakarta.validation.constraints.Size;
import java.util.List;

// Partial update: only non-null fields are applied. Sending schedule
// replaces the full 7-day week in one shot (spec section 11).
// bookingHorizonDays 0 means "sin límite" (stored as null), since null here
// already means "don't change it".
public record UpdateEstablishmentRequest(
        String businessName,
        String contactPhone,
        @Size(max = 200) String address,
        String timezone,
        Integer cancellationWindowHours,
        Integer bookingHorizonDays,
        Integer slotIntervalMinutes,
        Boolean onlineBookingEnabled,
        Boolean remindersEnabled,
        List<DaySchedule> schedule) {
}
