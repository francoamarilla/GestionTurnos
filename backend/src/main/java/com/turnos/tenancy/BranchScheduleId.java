package com.turnos.tenancy;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.util.Objects;
import java.util.UUID;

public class BranchScheduleId implements Serializable {

    private UUID branchId;
    private DayOfWeek dayOfWeek;

    public BranchScheduleId() {
    }

    public BranchScheduleId(UUID branchId, DayOfWeek dayOfWeek) {
        this.branchId = branchId;
        this.dayOfWeek = dayOfWeek;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BranchScheduleId that)) {
            return false;
        }
        return Objects.equals(branchId, that.branchId) && dayOfWeek == that.dayOfWeek;
    }

    @Override
    public int hashCode() {
        return Objects.hash(branchId, dayOfWeek);
    }
}
