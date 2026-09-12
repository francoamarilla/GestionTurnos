package com.turnos.tenancy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The MVP always creates exactly one Branch per Tenant automatically, with no
// management screen - modeled separately anyway so multi-branch later (spec
// section 15) is a data change, not a schema redesign.
@Entity
@Table(name = "branch")
@Getter
@Setter
@NoArgsConstructor
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private String address;
}
