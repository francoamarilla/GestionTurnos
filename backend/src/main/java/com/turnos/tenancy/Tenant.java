package com.turnos.tenancy;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String businessName;

    private String contactPhone;

    @Enumerated(EnumType.STRING)
    private TenantStatus status;

    private String businessCategory;

    private Integer cancellationWindowHours;

    private String timezone;

    private String address;

    // Null = sin límite: how far ahead a customer may book (V14).
    private Integer bookingHorizonDays;

    // Defaults mirror V14's, which only apply to rows Hibernate doesn't
    // write a value for - and it always does.
    private Integer slotIntervalMinutes = 30;

    private boolean onlineBookingEnabled = true;

    // 24 h and 2 h reminders; the confirmation and the cancellation notice
    // are not reminders and always go out.
    private boolean remindersEnabled = true;

    @CreationTimestamp
    private Instant createdAt;
}
