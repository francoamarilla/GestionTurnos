package com.turnos.professionals;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Maps professional_service (spec section 3.1's N:M ProfessionalService
// table). Named "...Assignment" here, not "ProfessionalService", since this
// class already needs @org.springframework.stereotype.Service on the
// business logic class in the same package.
@Entity
@Table(name = "professional_service")
@IdClass(ProfessionalServiceAssignmentId.class)
@Getter
@Setter
@NoArgsConstructor
public class ProfessionalServiceAssignment {

    @Id
    private UUID professionalId;

    @Id
    private UUID serviceId;
}
