package com.turnos.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchClosureRepository extends JpaRepository<BranchClosure, UUID> {

    List<BranchClosure> findByBranchId(UUID branchId);
}
