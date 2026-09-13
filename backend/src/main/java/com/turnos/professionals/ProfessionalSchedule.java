package com.turnos.professionals;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Same shape as BranchSchedule; can be a subset of the branch's own hours
// (spec section 3.1) - not cross-validated against it in the MVP.
@Entity
@Table(name = "professional_schedule")
@IdClass(ProfessionalScheduleId.class)
@Getter
@Setter
@NoArgsConstructor
public class ProfessionalSchedule {

    @Id
    private UUID professionalId;

    @Id
    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    private LocalTime openTime;

    private LocalTime closeTime;

    @Column(name = "is_closed")
    private boolean closed;
}
