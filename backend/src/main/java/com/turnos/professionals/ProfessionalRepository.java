package com.turnos.professionals;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfessionalRepository extends JpaRepository<Professional, UUID> {

    Page<Professional> findByBranchId(UUID branchId, Pageable pageable);

    Page<Professional> findByBranchIdAndActive(UUID branchId, boolean active, Pageable pageable);
}
