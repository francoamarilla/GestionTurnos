package com.turnos.agentapi;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns the customer's literal words ("corte", "Juan", "el jueves", "10:30")
// into ids, a date and a time, and says how sure it is (propuesta section
// 6.1). Deliberately code, not the model: precision is measured by explicit,
// testable rules, because models are bad at judging their own certainty.
//
// blockers   = cannot build a concrete draft at all (model must ask/search).
// ambiguities = a concrete draft exists but the customer must confirm it.
public final class BookingDraftResolver {

    public record Named(UUID id, String name) {
    }

    public record Resolution(
            UUID serviceId,
            UUID professionalId,
            boolean anyProfessional,
            LocalDate date,
            LocalTime time,
            List<String> ambiguities,
            List<String> blockers,
            List<String> candidates) {

        public boolean concrete() {
            return blockers.isEmpty();
        }
    }

    // Ambiguities about when, the only ones DATE_TIME_ONLY confirms on.
    static final Set<String> DATE_TIME_AMBIGUITIES =
            Set.of("date_same_weekday", "time_approximate", "time_am_pm");

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "lunes", DayOfWeek.MONDAY,
            "martes", DayOfWeek.TUESDAY,
            "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY,
            "viernes", DayOfWeek.FRIDAY,
            "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private static final Set<String> ANY_PROFESSIONAL =
            Set.of("cualquiera", "cualquier", "indistinto", "el que sea", "da igual", "cualquiera esta bien");

    private static final Pattern DAY_MONTH = Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$");
    private static final Pattern WEEKDAY_DAY = Pattern.compile("^(?:el )?([a-z]+)(?: (\\d{1,2}))?$");
    private static final Pattern DAY_ONLY = Pattern.compile("^(?:el )?(?:dia )?(\\d{1,2})$");
    private static final Pattern EXACT_TIME =
            Pattern.compile("^(?:a las |a la )?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:hs|h|horas|hrs)?$");
    private static final Pattern APPROX_TIME = Pattern.compile(
            "^(?:tipo|como a las|como|a eso de las|cerca de las|mas o menos a las|mas o menos)\\s+(.+)$");
    private static final Pattern RANGE_WORDS = Pattern.compile(
            "manana|tarde|noche|mediodia|despues|antes|entre|desde|hasta|temprano|primera hora|ultima hora");

    private BookingDraftResolver() {
    }

    public static Resolution resolve(
            String serviceText,
            String professionalText,
            String dateText,
            String timeText,
            List<Named> services,
            Function<UUID, List<Named>> professionalsForService,
            LocalDate today) {
        List<String> ambiguities = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> candidates = new ArrayList<>();

        // Service: one match = explicit; several = ask; none = ask.
        UUID serviceId = null;
        List<Named> serviceMatches = match(serviceText, services);
        if (serviceMatches.size() == 1) {
            serviceId = serviceMatches.get(0).id();
        } else if (serviceMatches.isEmpty()) {
            blockers.add("service_not_found");
            services.forEach(s -> candidates.add(s.name()));
        } else {
            blockers.add("service_ambiguous");
            serviceMatches.forEach(s -> candidates.add(s.name()));
        }

        // Professional: named and unique, or "cualquiera", is explicit. Not
        // said at all is fine with a single professional, ambiguous with more.
        UUID professionalId = null;
        boolean anyProfessional = false;
        if (serviceId != null) {
            List<Named> professionals = professionalsForService.apply(serviceId);
            String p = normalize(professionalText);
            if (professionals.isEmpty()) {
                blockers.add("no_professional_offers_service");
            } else if (p.isEmpty()) {
                if (professionals.size() == 1) {
                    professionalId = professionals.get(0).id();
                } else {
                    anyProfessional = true;
                    ambiguities.add("professional_not_specified");
                }
            } else if (ANY_PROFESSIONAL.contains(p)) {
                anyProfessional = true;
            } else {
                List<Named> proMatches = match(professionalText, professionals);
                if (proMatches.size() == 1) {
                    professionalId = proMatches.get(0).id();
                } else {
                    blockers.add(proMatches.isEmpty() ? "professional_not_found" : "professional_ambiguous");
                    (proMatches.isEmpty() ? professionals : proMatches).forEach(x -> candidates.add(x.name()));
                }
            }
        }

        LocalDate date = resolveDate(dateText, today, ambiguities, blockers);
        LocalTime time = resolveTime(timeText, ambiguities, blockers);

        return new Resolution(
                serviceId, professionalId, anyProfessional, date, time, ambiguities, blockers, candidates);
    }

    // AUTO only when nothing was inferred and the owner allows it (D7).
    public static String mode(String confirmationMode, List<String> ambiguities) {
        return switch (confirmationMode == null ? "STANDARD" : confirmationMode) {
            case "ALWAYS" -> "CONFIRM";
            case "DATE_TIME_ONLY" -> ambiguities.stream().anyMatch(DATE_TIME_AMBIGUITIES::contains) ? "CONFIRM" : "AUTO";
            default -> ambiguities.isEmpty() ? "AUTO" : "CONFIRM";
        };
    }

    static LocalDate resolveDate(String text, LocalDate today, List<String> ambiguities, List<String> blockers) {
        String d = normalize(text);
        if (d.isEmpty()) {
            blockers.add("date_missing");
            return null;
        }
        if (d.equals("hoy")) {
            return today;
        }
        if (d.equals("manana")) {
            return today.plusDays(1);
        }
        if (d.equals("pasado manana")) {
            return today.plusDays(2);
        }
        try {
            return LocalDate.parse(d); // ISO, which the model sometimes sends
        } catch (DateTimeParseException ignored) {
            // not ISO, keep trying
        }
        Matcher m = DAY_MONTH.matcher(d);
        if (m.matches()) {
            int year = m.group(3) == null ? today.getYear() : Integer.parseInt(m.group(3));
            if (year < 100) {
                year += 2000;
            }
            try {
                LocalDate date = LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                // "5/1" in November means next January.
                return m.group(3) == null && date.isBefore(today) ? date.plusYears(1) : date;
            } catch (java.time.DateTimeException e) {
                blockers.add("date_unclear");
                return null;
            }
        }
        m = WEEKDAY_DAY.matcher(d);
        if (m.matches() && WEEKDAYS.containsKey(m.group(1))) {
            DayOfWeek weekday = WEEKDAYS.get(m.group(1));
            LocalDate date = today;
            while (date.getDayOfWeek() != weekday) {
                date = date.plusDays(1);
            }
            if (m.group(2) != null) {
                // "jueves 19": the number wins if it agrees within two weeks.
                int day = Integer.parseInt(m.group(2));
                for (LocalDate c = date; c.isBefore(today.plusDays(15)); c = c.plusWeeks(1)) {
                    if (c.getDayOfMonth() == day) {
                        return c;
                    }
                }
                blockers.add("date_weekday_mismatch");
                return null;
            }
            if (date.equals(today)) {
                // "el jueves" said on a Thursday: today or next week?
                ambiguities.add("date_same_weekday");
            }
            return date;
        }
        m = DAY_ONLY.matcher(d);
        if (m.matches()) {
            int day = Integer.parseInt(m.group(1));
            LocalDate base = today.withDayOfMonth(1);
            for (int i = 0; i < 2; i++) {
                LocalDate month = base.plusMonths(i);
                if (day <= month.lengthOfMonth() && !month.withDayOfMonth(day).isBefore(today)) {
                    return month.withDayOfMonth(day);
                }
            }
        }
        // "la semana que viene", "a fin de mes"...
        blockers.add("date_unclear");
        return null;
    }

    static LocalTime resolveTime(String text, List<String> ambiguities, List<String> blockers) {
        String t = normalize(text);
        if (t.isEmpty()) {
            blockers.add("time_missing");
            return null;
        }
        boolean approximate = false;
        Matcher approx = APPROX_TIME.matcher(t);
        if (approx.matches()) {
            approximate = true;
            t = approx.group(1).replaceAll("\\s*y pico$", "");
        } else if (t.endsWith(" y pico")) {
            approximate = true;
            t = t.replaceAll("\\s*y pico$", "");
        }
        Matcher m = EXACT_TIME.matcher(t);
        if (!m.matches()) {
            blockers.add(RANGE_WORDS.matcher(t).find() ? "time_range" : "time_unclear");
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) {
            blockers.add("time_unclear");
            return null;
        }
        if (hour >= 1 && hour <= 7) {
            // Nobody gets a haircut at 6 a.m.: read as p.m., but say so.
            hour += 12;
            ambiguities.add("time_am_pm");
        }
        if (approximate) {
            ambiguities.add("time_approximate");
        }
        return LocalTime.of(hour, minute);
    }

    // Exact (normalized) name first; otherwise every name that contains the
    // text or is contained by it.
    static List<Named> match(String text, List<Named> options) {
        String t = normalize(text);
        if (t.isEmpty()) {
            return List.of();
        }
        List<Named> exact = options.stream().filter(o -> normalize(o.name()).equals(t)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return options.stream()
                .filter(o -> {
                    String n = normalize(o.name());
                    return n.contains(t) || t.contains(n) || n.split(" ")[0].equals(t);
                })
                .toList();
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase().replaceAll("[^a-z0-9:./+ -]", " ").replaceAll("\\s+", " ").strip();
    }
}
