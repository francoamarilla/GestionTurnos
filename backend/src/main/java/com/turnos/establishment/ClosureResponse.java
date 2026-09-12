package com.turnos.establishment;

import com.turnos.tenancy.BranchClosure;
import java.time.LocalDate;
import java.util.UUID;

public record ClosureResponse(UUID id, LocalDate date, String reason) {

    static ClosureResponse from(BranchClosure closure) {
        return new ClosureResponse(closure.getId(), closure.getDate(), closure.getReason());
    }
}
