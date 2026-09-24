package com.turnos.professionals;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Same shape as BranchSchedule: one row per range, no row = doesn't work
// that day. Can be a subset of the branch's own hours (spec section 3.1) -
// not cross-validated against it in the MVP.
@Entity
@Table(name = "professional_schedule")
@Getter
@Setter
@NoArgsConstructor
public class ProfessionalSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID professionalId;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    private LocalTime openTime;

    private LocalTime closeTime;
}
