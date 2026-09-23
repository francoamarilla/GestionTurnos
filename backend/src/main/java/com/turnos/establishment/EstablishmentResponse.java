package com.turnos.establishment;

import java.util.List;

// bookingHorizonDays null = sin límite.
public record EstablishmentResponse(
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
