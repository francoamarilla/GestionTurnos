package com.turnos.establishment;

import com.turnos.tenancy.TenantStatus;
import java.util.List;

// bookingHorizonDays null = sin límite. status is read-only here: only
// POST /deactivate (#9) moves it, and only the Super Admin brings it back.
public record EstablishmentResponse(
        TenantStatus status,
        String businessName,
        String contactPhone,
        String address,
        Integer cancellationWindowHours,
        Integer bookingHorizonDays,
        Integer slotIntervalMinutes,
        boolean onlineBookingEnabled,
        boolean remindersEnabled,
        String timezone,
        List<DaySchedule> schedule) {
}
