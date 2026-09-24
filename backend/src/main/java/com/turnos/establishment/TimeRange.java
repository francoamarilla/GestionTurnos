package com.turnos.establishment;

import java.time.LocalTime;

public record TimeRange(LocalTime openTime, LocalTime closeTime) {
}
