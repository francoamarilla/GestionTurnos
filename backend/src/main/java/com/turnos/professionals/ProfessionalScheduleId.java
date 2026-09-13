package com.turnos.professionals;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.util.Objects;
import java.util.UUID;

public class ProfessionalScheduleId implements Serializable {

    private UUID professionalId;
    private DayOfWeek dayOfWeek;

    public ProfessionalScheduleId() {
    }

    public ProfessionalScheduleId(UUID professionalId, DayOfWeek dayOfWeek) {
        this.professionalId = professionalId;
        this.dayOfWeek = dayOfWeek;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalScheduleId that)) {
            return false;
        }
        return Objects.equals(professionalId, that.professionalId) && dayOfWeek == that.dayOfWeek;
    }

    @Override
    public int hashCode() {
        return Objects.hash(professionalId, dayOfWeek);
    }
}
