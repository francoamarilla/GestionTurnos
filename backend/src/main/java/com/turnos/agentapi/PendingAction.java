package com.turnos.agentapi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// type: BOOKING | CANCELLATION | RESCHEDULE. mode: AUTO | CONFIRM.
// status: PENDING | COMMITTED.
@Entity
@Table(name = "pending_action")
@Getter
@Setter
@NoArgsConstructor
public class PendingAction {

    static final long TTL_SECONDS = 600;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private UUID conversationId;

    private String type;

    private UUID appointmentId;

    private UUID professionalId;

    private UUID serviceId;

    private Instant startTime;

    private String summary;

    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> ambiguities = new ArrayList<>();

    private String status = "PENDING";

    private Instant createdAt = Instant.now();

    private Instant expiresAt = Instant.now().plusSeconds(TTL_SECONDS);

    boolean isOpen() {
        return "PENDING".equals(status) && Instant.now().isBefore(expiresAt);
    }
}
