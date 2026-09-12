package com.turnos.establishment;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record DaySchedule(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean closed) {
}
