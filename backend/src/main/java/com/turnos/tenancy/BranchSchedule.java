package com.turnos.tenancy;

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

// One row per range: a day can be split (9-13 and 16-20), and a day with no
// rows is a day the branch doesn't open (V15).
@Entity
@Table(name = "branch_schedule")
@Getter
@Setter
@NoArgsConstructor
public class BranchSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID branchId;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    private LocalTime openTime;

    private LocalTime closeTime;
}
