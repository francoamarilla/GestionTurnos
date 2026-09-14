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

    // CAST(:search AS string) - without it, a null :search (no search term
    // given) makes Postgres unable to infer the bind parameter's type inside
    // LOWER(CONCAT(...)), and it defaults to bytea: "function lower(bytea)
    // does not exist". The cast forces text regardless of nullness, so the
    // short-circuiting :search IS NULL branch still works exactly as before.
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND "
            + "(:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR c.phone LIKE CONCAT('%', CAST(:search AS string), '%'))")
    Page<Customer> search(@Param("tenantId") UUID tenantId, @Param("search") String search, Pageable pageable);
}
