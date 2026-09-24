package com.turnos.professionals;

import java.time.DayOfWeek;
import java.util.List;

// A day with an empty ranges list is a day that isn't worked; a split day
// carries more than one range (ENDPOINTS-PENDIENTES.md #7).
public record DaySchedule(DayOfWeek dayOfWeek, List<TimeRange> ranges) {
}
