package com.turnos.whatsapp;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 1:1 with Tenant, modeled separately since it has its own technical
// lifecycle (spec section 3.1). verificationStatus is a free string until a
// BSP is chosen (spec section 13) - don't invent its value set.
@Entity
@Table(name = "whatsapp_integration")
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private String wabaId;

    private String phoneNumber;

    private String verificationStatus;
}
