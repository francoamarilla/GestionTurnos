package com.turnos.establishment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turnos.AbstractIntegrationTest;
import com.turnos.tenancy.TenantSessionContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

// Malformed weeks must come back as 400 and leave the stored schedule
// alone - found reviewing #7: both cases used to reach the write and blow
// up as a 500 after the old week was already deleted.
class ScheduleValidationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EstablishmentService establishmentService;

    @Autowired
    private TenantSessionContext tenantSessionContext;

    private UUID tenantId;

    @BeforeEach
    void setUpFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    connection.prepareStatement("SELECT set_config('app.is_super_admin', 'true', false)")) {
                ps.execute();
            }
            tenantId = insert(connection, "INSERT INTO tenant (business_name, contact_phone, status)"
                    + " VALUES ('Schedule Validation Test', '+540000000000', 'ACTIVE') RETURNING id");
            insert(connection, "INSERT INTO branch (tenant_id) VALUES ('" + tenantId + "') RETURNING id");
        }
        tenantSessionContext.apply(false, tenantId);
        establishmentService.update(tenantId, request(week(List.of(new TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
    }

    // {"dayOfWeek":"MONDAY"} with no ranges at all reads as a closed day,
    // like an empty list - it used to reach the write and NPE into a 500.
    @Test
    void treatsAMissingRangesListAsAClosedDay() {
        List<DaySchedule> schedule = new ArrayList<>(week(List.of(new TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))));
        schedule.set(0, new DaySchedule(DayOfWeek.MONDAY, null));

        List<DaySchedule> stored = establishmentService.update(tenantId, request(schedule)).schedule();

        assertEquals(0, stored.get(0).ranges().size());
        assertEquals(1, stored.get(1).ranges().size());
    }

    @Test
    void rejectsARangeWithoutTimesEvenNextToAnotherRange() {
        List<DaySchedule> schedule = new ArrayList<>(week(List.of(new TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))));
        schedule.set(
                0,
                new DaySchedule(
                        DayOfWeek.MONDAY,
                        Arrays.asList(new TimeRange(LocalTime.of(9, 0), LocalTime.of(13, 0)), new TimeRange(null, null))));

        assertEquals(400, badRequest(schedule));
        assertKeptTheStoredWeek();
    }

    private int badRequest(List<DaySchedule> schedule) {
        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> establishmentService.update(tenantId, request(schedule)));
        return thrown.getStatusCode().value();
    }

    private void assertKeptTheStoredWeek() {
        List<DaySchedule> stored = establishmentService.get(tenantId).schedule();
        assertEquals(7, stored.size());
        assertEquals(1, stored.get(0).ranges().size());
        assertEquals(LocalTime.of(9, 0), stored.get(0).ranges().get(0).openTime());
    }

    private static UpdateEstablishmentRequest request(List<DaySchedule> schedule) {
        return new UpdateEstablishmentRequest(null, null, null, null, null, null, null, null, null, schedule);
    }

    private static List<DaySchedule> week(List<TimeRange> ranges) {
        return Arrays.stream(DayOfWeek.values())
                .map(day -> new DaySchedule(day, ranges))
                .toList();
    }

    private static UUID insert(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject("id");
        }
    }
}
