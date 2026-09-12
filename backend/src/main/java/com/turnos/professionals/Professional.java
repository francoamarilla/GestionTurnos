package com.turnos.professionals;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "professional")
@Getter
@Setter
@NoArgsConstructor
public class Professional {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID branchId;

    private String name;

    private String contactPhone;

    // Soft delete only - never physically removed, so past appointment
    // history stays intact (spec section 4, rule 21).
    private boolean active;
}
