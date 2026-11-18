package com.turnos.agentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.agentapi.BookingDraftResolver.Named;
import com.turnos.agentapi.BookingDraftResolver.Resolution;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// One case per row of propuesta-arquitectura-ia.md section 6.1: this table
// decides whether the agent books without asking.
class BookingDraftResolverTest {

    // Tuesday 17/11/2026.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final Named corte = new Named(UUID.randomUUID(), "Corte");
    private final Named corteBarba = new Named(UUID.randomUUID(), "Corte + barba");
    private final Named barba = new Named(UUID.randomUUID(), "Barba");
    private final Named juan = new Named(UUID.randomUUID(), "Juan Pérez");
    private final Named pedro = new Named(UUID.randomUUID(), "Pedro");

    private Resolution resolve(String service, String professional, String date, String time) {
        return BookingDraftResolver.resolve(
                service, professional, date, time, List.of(corte, corteBarba, barba), id -> List.of(juan, pedro), TODAY);
    }

    @Test
    void fullyPreciseRequestIsAuto() {
        Resolution r = resolve("corte", "Juan", "jueves 19", "10:30");

        assertTrue(r.concrete());
        assertEquals(corte.id(), r.serviceId());
        assertEquals(juan.id(), r.professionalId());
        assertEquals(LocalDate.of(2026, 11, 19), r.date());
        assertEquals(LocalTime.of(10, 30), r.time());
        assertEquals("AUTO", BookingDraftResolver.mode("STANDARD", r.ambiguities()));
    }

    @Test
    void serviceMatchingSeveralIsABlockerWithCandidates() {
        Resolution r = resolve("cort", "Juan", "mañana", "10");

        assertFalse(r.concrete());
        assertTrue(r.blockers().contains("service_ambiguous"));
        assertEquals(List.of("Corte", "Corte + barba"), r.candidates());
    }

    @Test
    void unknownServiceListsTheMenu() {
        Resolution r = resolve("tintura", null, "mañana", "10");

        assertTrue(r.blockers().contains("service_not_found"));
        assertEquals(3, r.candidates().size());
    }

    @Test
    void professionalNotSaidWithSeveralAvailableIsAmbiguousButConcrete() {
        Resolution r = resolve("barba", "", "mañana", "11:00");

        assertTrue(r.concrete());
        assertTrue(r.anyProfessional());
        assertEquals(List.of("professional_not_specified"), r.ambiguities());
        assertEquals("CONFIRM", BookingDraftResolver.mode("STANDARD", r.ambiguities()));
    }

    @Test
    void cualquieraIsAnExplicitChoice() {
        Resolution r = resolve("barba", "cualquiera", "mañana", "11:00");

        assertTrue(r.anyProfessional());
        assertTrue(r.ambiguities().isEmpty());
    }

    @Test
    void unknownProfessionalIsABlocker() {
        assertTrue(resolve("corte", "Martín", "mañana", "10").blockers().contains("professional_not_found"));
    }

    @Test
    void relativeDatesWithOneReadingAreExplicit() {
        assertEquals(TODAY.plusDays(1), resolve("corte", "Juan", "mañana", "10").date());
        assertEquals(LocalDate.of(2026, 11, 20), resolve("corte", "Juan", "el viernes", "10").date());
        assertEquals(LocalDate.of(2026, 11, 25), resolve("corte", "Juan", "25/11", "10").date());
        assertEquals(LocalDate.of(2027, 1, 5), resolve("corte", "Juan", "5/1", "10").date());
        assertEquals(LocalDate.of(2026, 11, 19), resolve("corte", "Juan", "2026-11-19", "10").date());
    }

    @Test
    void sameWeekdayAsTodayIsAmbiguous() {
        Resolution r = resolve("corte", "Juan", "el martes", "18:00");

        assertTrue(r.concrete());
        assertTrue(r.ambiguities().contains("date_same_weekday"));
    }

    @Test
    void vagueDatesAreBlockers() {
        assertTrue(resolve("corte", "Juan", "la semana que viene", "10").blockers().contains("date_unclear"));
        assertTrue(resolve("corte", "Juan", "a fin de mes", "10").blockers().contains("date_unclear"));
        assertTrue(resolve("corte", "Juan", "jueves 20", "10").blockers().contains("date_weekday_mismatch"));
    }

    @Test
    void timeRangesAreBlockersAndApproximateTimesAreAmbiguous() {
        assertTrue(resolve("corte", "Juan", "mañana", "a la mañana").blockers().contains("time_range"));
        assertTrue(resolve("corte", "Juan", "mañana", "después de las 6").blockers().contains("time_range"));

        Resolution tipo = resolve("corte", "Juan", "mañana", "tipo 10");
        assertEquals(LocalTime.of(10, 0), tipo.time());
        assertTrue(tipo.ambiguities().contains("time_approximate"));
    }

    @Test
    void earlyHoursAreReadAsAfternoonAndFlagged() {
        Resolution r = resolve("corte", "Juan", "mañana", "a las 6");

        assertEquals(LocalTime.of(18, 0), r.time());
        assertTrue(r.ambiguities().contains("time_am_pm"));
    }

    @Test
    void exactTimeFormats() {
        assertEquals(LocalTime.of(10, 30), resolve("corte", "Juan", "mañana", "10.30").time());
        assertEquals(LocalTime.of(17, 0), resolve("corte", "Juan", "mañana", "17hs").time());
        assertNull(resolve("corte", "Juan", "mañana", "").time());
    }

    @Test
    void confirmationModes() {
        assertEquals("CONFIRM", BookingDraftResolver.mode("ALWAYS", List.of()));
        assertEquals("AUTO", BookingDraftResolver.mode("DATE_TIME_ONLY", List.of("professional_not_specified")));
        assertEquals("CONFIRM", BookingDraftResolver.mode("DATE_TIME_ONLY", List.of("time_approximate")));
        assertEquals("CONFIRM", BookingDraftResolver.mode("STANDARD", List.of("possible_duplicate")));
    }
}
