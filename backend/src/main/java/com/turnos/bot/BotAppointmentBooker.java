package com.turnos.bot;

import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentService;
import com.turnos.appointments.CreateAppointmentRequest;
import com.turnos.appointments.RescheduleAppointmentRequest;
import com.turnos.tenancy.TenantSessionContext;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Isolates the one DB write in the whole bot exchange that can legitimately
// fail with a real constraint violation (spec section 4 rule 4's exclusion
// constraint, when two customers race for the same slot) into its own
// transaction/connection. A failed INSERT aborts the underlying Postgres
// transaction outright - Postgres has no partial rollback without an
// explicit SAVEPOINT - so if this ran inside BotConversationService's own
// long-lived @Transactional handleMessage(), catching the resulting
// ResponseStatusException there and trying to keep going (re-offer slots,
// save state) would fail every subsequent statement with "current
// transaction is aborted", surfacing as UnexpectedRollbackException at
// commit time. REQUIRES_NEW keeps that failure - and its automatic
// rollback - contained to just this attempt; the bot's own transaction,
// on its own untouched connection, is free to continue right after.
//
// Package-private: BotConversationService is this class's only caller.
@org.springframework.stereotype.Service
class BotAppointmentBooker {

    private final TenantSessionContext tenantSessionContext;
    private final AppointmentService appointmentService;

    BotAppointmentBooker(TenantSessionContext tenantSessionContext, AppointmentService appointmentService) {
        this.tenantSessionContext = tenantSessionContext;
        this.appointmentService = appointmentService;
    }

    // No try/finally reset here on purpose: on the failure path the
    // connection's transaction is already aborted at the Postgres level,
    // and issuing set_config() against an aborted transaction itself
    // throws, masking the real (409) error. Spring's proxy rolls the
    // nested transaction back automatically once the exception leaves this
    // method - that's the cleanup. Leftover GUCs on a committed/rolled-back
    // connection going back to the pool are harmless: every caller applies
    // its own tenant context before touching an RLS-protected table anyway.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AppointmentResponse attemptCreate(UUID tenantId, CreateAppointmentRequest request) {
        tenantSessionContext.apply(false, tenantId);
        return appointmentService.create(tenantId, request);
    }

    // Same isolation need as attemptCreate: rescheduleAsCustomer ends in the
    // same createInternal() -> exclusion constraint path, so a losing race
    // for the *new* slot has the identical abort-the-transaction problem.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AppointmentResponse attemptReschedule(
            UUID tenantId, UUID customerId, UUID appointmentId, RescheduleAppointmentRequest request) {
        tenantSessionContext.apply(false, tenantId);
        return appointmentService.rescheduleAsCustomer(tenantId, customerId, appointmentId, request);
    }
}
