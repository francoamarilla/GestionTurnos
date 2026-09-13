package com.turnos.customers;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByTenantIdAndPhone(UUID tenantId, String phone);

    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND "
            + "(:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "OR c.phone LIKE CONCAT('%', :search, '%'))")
    Page<Customer> search(@Param("tenantId") UUID tenantId, @Param("search") String search, Pageable pageable);
}
