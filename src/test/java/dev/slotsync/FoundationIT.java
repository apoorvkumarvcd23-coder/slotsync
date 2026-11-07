package dev.slotsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class FoundationIT {
    private static final String ROOM = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_ROOM = "10000000-0000-0000-0000-000000000002";
    private static final String ACTOR = "20000000-0000-0000-0000-000000000001";
    private static final String START = "2026-11-10T10:00:00Z";
    private static final String END = "2026-11-10T11:00:00Z";
    private static final String URL;
    private static final String USER;
    private static final String PASSWORD;

    static {
        String externalUrl = System.getenv("SLOTSYNC_TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("slotsync_test");
            postgres.start(); // No disabled/skipped fallback: PostgreSQL is a required check.
            URL = postgres.getJdbcUrl();
            USER = postgres.getUsername();
            PASSWORD = postgres.getPassword();
        } else {
            if (!externalUrl.endsWith("/slotsync_test")) {
                throw new IllegalArgumentException("Integration checks require a dedicated slotsync_test database");
            }
            URL = externalUrl;
            USER = requiredEnv("SLOTSYNC_TEST_DB_USER");
            PASSWORD = requiredEnv("SLOTSYNC_TEST_DB_PASSWORD");
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;

    @BeforeEach
    void cleanTestBookings() {
        jdbc.update("DELETE FROM bookings");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id, username, password_hash, role, enabled) VALUES (?::uuid, 'test-actor', 'not-a-login-hash', 'REQUESTER', false)", ACTOR);
    }

    @Test
    void migrationsValidateAndCanRunAgainWithoutDuplicatingFixtures() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources", Integer.class)).isEqualTo(3);
    }

    @Test
    void catalogueIsDeterministicAndContainsOnlyPublicFields() throws Exception {
        mvc.perform(get("/api/resources")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("KIT-PROJECTOR"))
                .andExpect(jsonPath("$[1].name").value("Atlas meeting room"))
                .andExpect(jsonPath("$[1].capacity").value(8))
                .andExpect(jsonPath("$[1].type").value("ROOM"))
                .andExpect(jsonPath("$[1].passwordHash").doesNotExist());
    }

    @Test
    void inactiveResourcesAreHidden() throws Exception {
        jdbc.update("UPDATE resources SET active = false WHERE id = ?::uuid", ROOM);
        try {
            mvc.perform(get("/api/resources")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        } finally {
            jdbc.update("UPDATE resources SET active = true WHERE id = ?::uuid", ROOM);
        }
    }

    @Test
    void readinessIncludesDatabaseAndHidesOperationalDetails() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }

    @Test
    void writesAreClosedUntilAuthenticatedWorkflowExists() throws Exception {
        mvc.perform(post("/api/resources").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingAndApprovedSlotsBothPreventOverlap() {
        for (String status : List.of("PENDING", "APPROVED")) {
            jdbc.update("DELETE FROM bookings");
            insert(ROOM, START, END, status);
            assertSqlState("23P01", () -> insert(ROOM, "2026-11-10T10:30:00Z", "2026-11-10T11:30:00Z", "PENDING"));
        }
    }

    @Test
    void adjacentSlotsAndDifferentResourcesAreAllowed() {
        insert(ROOM, START, END, "PENDING");
        insert(ROOM, END, "2026-11-10T12:00:00Z", "APPROVED");
        insert(OTHER_ROOM, START, END, "PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(3);
    }

    @Test
    void cancelledAndRejectedSlotsDoNotHoldTime() {
        insert(ROOM, START, END, "CANCELLED");
        insert(ROOM, START, END, "REJECTED");
        insert(ROOM, START, END, "PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(3);
    }

    @Test
    void cancellationFreesTimeAndReactivationCannotBypassConstraint() {
        insert(ROOM, START, END, "APPROVED");
        jdbc.update("UPDATE bookings SET status = 'CANCELLED'");
        insert(ROOM, START, END, "PENDING");
        assertSqlState("23P01", () -> jdbc.update("UPDATE bookings SET status = 'APPROVED' WHERE status = 'CANCELLED'"));
    }

    @Test
    void equivalentTimeZonesStillConflict() {
        insert(ROOM, START, END, "PENDING");
        assertSqlState("23P01", () -> insert(ROOM, "2026-11-10T15:30:00+05:30", "2026-11-10T16:30:00+05:30", "APPROVED"));
    }

    @Test
    void zeroReversedAndInfiniteIntervalsAreRejected() {
        assertSqlState("23514", () -> insert(ROOM, START, START, "PENDING"));
        assertSqlState("23514", () -> insert(ROOM, END, START, "PENDING"));
        assertSqlState("23514", () -> insert(ROOM, START, "infinity", "PENDING"));
    }

    @Test
    void concurrentConnectionsProduceExactlyOneReservation() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var submissions = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET LOCAL lock_timeout = '10s'");
                    }
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO bookings(id, resource_id, owner_id, starts_at, ends_at, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, 'PENDING')")) {
                        statement.setString(1, UUID.randomUUID().toString());
                        statement.setString(2, ROOM);
                        statement.setString(3, ACTOR);
                        statement.setString(4, START);
                        statement.setString(5, END);
                        statement.executeUpdate();
                        connection.commit();
                        return "COMMITTED";
                    } catch (SQLException failure) {
                        connection.rollback();
                        return failure.getSQLState();
                    }
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = List.of(submissions.get(0).get(20, TimeUnit.SECONDS), submissions.get(1).get(20, TimeUnit.SECONDS));
            // Concurrent GiST checks can deadlock; either error must abort the loser.
            // API-level bounded transaction retry/409 translation belongs to CORE03.
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isIn("COMMITTED", "23P01", "40P01"));
            assertThat(outcomes.stream().filter("COMMITTED"::equals).count()).isEqualTo(1);
        } finally {
            start.countDown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
    }

    private void insert(String resource, String start, String end, String status) {
        jdbc.update("INSERT INTO bookings(id, resource_id, owner_id, starts_at, ends_at, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, ?)",
                UUID.randomUUID().toString(), resource, ACTOR, start, end, status);
    }

    private static void assertSqlState(String expected, Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected);
    }
}
