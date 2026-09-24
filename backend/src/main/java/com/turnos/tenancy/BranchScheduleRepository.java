package com.turnos.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchScheduleRepository extends JpaRepository<BranchSchedule, UUID> {

    List<BranchSchedule> findByBranchId(UUID branchId);

    void deleteByBranchId(UUID branchId);
}
