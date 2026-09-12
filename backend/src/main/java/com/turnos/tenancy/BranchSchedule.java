package com.turnos.tenancy;

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

// One row per day of week (spec section 3.1); a split morning/afternoon
// range is not modeled yet (open question, spec section 13).
@Entity
@Table(name = "branch_schedule")
@IdClass(BranchScheduleId.class)
@Getter
@Setter
@NoArgsConstructor
public class BranchSchedule {

    @Id
    private UUID branchId;

    @Id
    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    private LocalTime openTime;

    private LocalTime closeTime;

    @Column(name = "is_closed")
    private boolean closed;
}
