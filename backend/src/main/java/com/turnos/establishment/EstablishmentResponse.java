package com.turnos.establishment;

import java.util.List;

public record EstablishmentResponse(
        String businessName,
        String contactPhone,
        Integer cancellationWindowHours,
        String timezone,
        List<DaySchedule> schedule) {
}
