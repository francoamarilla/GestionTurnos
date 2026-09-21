package com.turnos.appointments;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

// durationMinutes and price are frozen copies of the Service's values at
// creation time (spec section 3.1) - they don't move if the catalog changes
// later. customerId is nullable: an anonymous walk-in is a valid Appointment.
@Entity
@Table(name = "appointment")
@Getter
@Setter
@NoArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID branchId;

    private UUID professionalId;

    private UUID serviceId;

    private UUID customerId;

    private Instant startTime;

    // Redundant with startTime + durationMinutes; see V7 migration comment
    // for why it's a stored column instead of computed on read.
    private Instant endTime;

    private Integer durationMinutes;

    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    @Enumerated(EnumType.STRING)
    private AppointmentOrigin origin;

    // Null = not recorded (see V11).
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    private UUID rescheduledFromAppointmentId;

    private UUID completedBy;

    private UUID noShowMarkedBy;

    private Instant completedAt;

    @CreationTimestamp
    private Instant createdAt;
}
