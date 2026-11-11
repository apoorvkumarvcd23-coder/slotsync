package dev.slotsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class BookingIT {
    static final UUID ALICE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String ROOM = "10000000-0000-0000-0000-000000000001";
    static final String OTHER = "10000000-0000-0000-0000-000000000002";
    static final String PASSWORD = "synthetic-test-password";
    static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { FoundationIT.database(registry); }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM bookings");
        jdbc.update("DELETE FROM users");
        for (String name : List.of("alice", "bob", "approver", "admin")) {
            UUID id = name.equals("alice") ? ALICE : name.equals("bob") ? BOB : UUID.nameUUIDFromBytes(name.getBytes());
            String role = name.equals("admin") ? "ADMIN" : name.equals("approver") ? "APPROVER" : "REQUESTER";
            jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,?)", id, name, HASH, role);
        }
    }

    @Test
    void actualSessionLoginRotatesCsrfAndLogoutInvalidatesSession() throws Exception {
        MvcResult anonymous = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        MockHttpSession initial = (MockHttpSession) anonymous.getRequest().getSession(false);
        String oldId = initial.getId();
        JsonNode initialToken = parse(anonymous);
        mvc.perform(post("/api/login").session(initial).param("username", "alice").param("password", PASSWORD)
                .header(initialToken.get("csrfHeader").asText(), initialToken.get("csrfToken").asText())).andExpect(status().isNoContent());
        assertThat(initial.getId()).isNotEqualTo(oldId);
        MvcResult current = mvc.perform(get("/api/session").session(initial))
                .andExpect(jsonPath("$.username").value("alice")).andExpect(jsonPath("$.role").value("REQUESTER"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode token = parse(current);
        mvc.perform(post("/api/logout").session(initial).header(token.get("csrfHeader").asText(), initialToken.get("csrfToken").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/logout").session(initial).header(token.get("csrfHeader").asText(), token.get("csrfToken").asText()))
                .andExpect(status().isNoContent());
        assertThat(initial.isInvalid()).isTrue();
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void badPasswordAndMissingCsrfCannotLogInOrWrite() throws Exception {
        mvc.perform(post("/api/login").param("username", "alice").param("password", PASSWORD)).andExpect(status().isForbidden());
        MvcResult tokenResult = mvc.perform(get("/api/session")).andReturn();
        JsonNode token = parse(tokenResult);
        mvc.perform(post("/api/login").session((MockHttpSession) tokenResult.getRequest().getSession(false))
                .header(token.get("csrfHeader").asText(), token.get("csrfToken").asText())
                .param("username", "alice").param("password", "wrong")).andExpect(status().isUnauthorized());
        Session alice = login("alice");
        mvc.perform(post("/api/bookings").session(alice.session).contentType("application/json")
                .content(body(ROOM, future(), future().plusSeconds(3600)))).andExpect(status().isForbidden());
        assertThat(count("bookings")).isZero();
    }

    @Test
    void wrongRolesCannotUseRequesterEndpoints() throws Exception {
        for (String role : List.of("approver", "admin")) {
            Session session = login(role);
            mvc.perform(get("/api/bookings").session(session.session)).andExpect(status().isForbidden());
            mvc.perform(authorized(post("/api/bookings"), session).header("Idempotency-Key", "role-test-key")
                    .contentType("application/json").content(body(ROOM, future(), future().plusSeconds(3600)))).andExpect(status().isForbidden());
        }
    }

    @Test
    void canonicalReplayChangedPayloadAndActorScopingAreTransactional() throws Exception {
        Session alice = login("alice");
        Instant start = future(), end = start.plusSeconds(3600);
        MvcResult first = create(alice, "stable-request", body(ROOM, start, end));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String id = parse(first).get("id").asText();
        String equivalent = json.writeValueAsString(Map.of("resourceId", ROOM,
                "startsAt", start.atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toString(),
                "endsAt", end.atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toString()));
        MvcResult replay = create(alice, "stable-request", equivalent);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(parse(replay).get("id").asText()).isEqualTo(id);
        assertThat(create(alice, "stable-request", body(OTHER, start, end)).getResponse().getStatus()).isEqualTo(409);
        assertThat(create(login("bob"), "stable-request", body(OTHER, start, end)).getResponse().getStatus()).isEqualTo(201);
        assertThat(count("bookings")).isEqualTo(2);
        assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(count("audit_events")).isEqualTo(2);
    }

    @Test
    void wrongOwnerCannotReadOrCancelAndOwnListIsScoped() throws Exception {
        Session alice = login("alice"), bob = login("bob");
        String id = parse(create(alice, "owner-request", body(ROOM, future(), future().plusSeconds(3600)))).get("id").asText();
        mvc.perform(get("/api/bookings/" + id).session(bob.session)).andExpect(status().isNotFound());
        mvc.perform(authorized(post("/api/bookings/" + id + "/cancel"), bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings").session(bob.session)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/bookings").session(alice.session)).andExpect(jsonPath("$.length()").value(1));
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void cancellationIsRepeatableFreesSlotAndCreateReplayRetainsOriginalResponse() throws Exception {
        Session alice = login("alice");
        Instant start = future(), end = start.plusSeconds(3600);
        String payload = body(ROOM, start, end);
        String id = parse(create(alice, "cancel-request", payload)).get("id").asText();
        assertThat(create(login("bob"), "conflicting-request", payload).getResponse().getStatus()).isEqualTo(409);
        for (int i = 0; i < 2; i++) {
            mvc.perform(authorized(post("/api/bookings/" + id + "/cancel"), alice)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        assertThat(parse(create(alice, "cancel-request", payload)).get("status").asText()).isEqualTo("PENDING");
        assertThat(create(alice, "replacement-request", payload).getResponse().getStatus()).isEqualTo(201);
        assertThat(count("audit_events")).isEqualTo(3);
    }

    @Test
    void adjacentSlotsWorkAndInactiveResourcesRejectNewBookings() throws Exception {
        Session alice = login("alice");
        Instant start = future(), boundary = start.plusSeconds(3600);
        assertThat(create(alice, "adjacent-one", body(ROOM, start, boundary)).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(alice, "adjacent-two", body(ROOM, boundary, boundary.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(201);
        jdbc.update("UPDATE resources SET active=false WHERE id=?::uuid", OTHER);
        try {
            assertThat(create(alice, "inactive-resource", body(OTHER, start, boundary)).getResponse().getStatus()).isEqualTo(404);
        } finally { jdbc.update("UPDATE resources SET active=true WHERE id=?::uuid", OTHER); }
    }

    @Test
    void invalidNaiveNullAndPastTimesAreRejectedWithoutWrites() throws Exception {
        Session alice = login("alice");
        Instant start = future();
        for (String payload : List.of(body(ROOM, start, start), body(ROOM, start, start.minusSeconds(1)),
                body(ROOM, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)),
                body(ROOM, start, start.plusSeconds(25*3600)),
                "{\"resourceId\":\"" + ROOM + "\",\"startsAt\":\"2027-01-01T10:00:00\",\"endsAt\":\"2027-01-01T11:00:00\"}",
                "{\"resourceId\":\"" + ROOM + "\",\"startsAt\":null,\"endsAt\":null}")) {
            assertThat(create(alice, "invalid-interval", payload).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(create(alice, "short", body(ROOM, start, start.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(400);
        assertThat(count("bookings")).isZero();
    }

    @Test
    void concurrentOverlapsProduceOneCreatedAndOneConflict() throws Exception {
        Session alice = login("alice"), bob = login("bob");
        String payload = body(ROOM, future(), future().plusSeconds(3600));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> create(alice, "concurrent-alice", payload).getResponse().getStatus());
            var b = workers.submit(() -> create(bob, "concurrent-bob", payload).getResponse().getStatus());
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(1);
    }

    @Test
    void concurrentSameActorReplayCreatesExactlyOneBooking() throws Exception {
        Session alice = login("alice");
        String payload = body(ROOM, future(), future().plusSeconds(3600));
        try (var workers = Executors.newFixedThreadPool(3)) {
            var calls = java.util.stream.IntStream.range(0, 3).mapToObj(i ->
                    workers.submit(() -> create(alice, "simultaneous-replay", payload))).toList();
            var results = calls.stream().map(call -> {
                try { return call.get(20, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(201, 200, 200);
            assertThat(results.stream().map(result -> { try { return parse(result).get("id").asText(); } catch(Exception e) { throw new RuntimeException(e); } }).distinct().count()).isEqualTo(1);
        }
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void databaseAuditFailureRollsBackBookingAndRequestKey() throws Exception {
        Session alice = login("alice");
        installFailure("RAISE EXCEPTION 'synthetic publication failure';");
        try {
            assertThat(create(alice, "rollback-request", body(ROOM, future(), future().plusSeconds(3600))).getResponse().getStatus()).isEqualTo(503);
            assertNoWrites();
        } finally { removeFailure(); }
    }

    @Test
    void deadlockRetriesWholeTransactionAndCommitsOneAudit() throws Exception {
        Session alice = login("alice");
        installFailure("IF nextval('slotsync_test_retry') < 3 THEN RAISE EXCEPTION 'synthetic deadlock' USING ERRCODE='40P01'; END IF;");
        try {
            assertThat(create(alice, "retry-request", body(ROOM, future(), future().plusSeconds(3600))).getResponse().getStatus()).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT last_value FROM slotsync_test_retry", Integer.class)).isEqualTo(3);
            assertThat(count("bookings")).isEqualTo(1);
            assertThat(count("audit_events")).isEqualTo(1);
        } finally { removeFailure(); }
    }

    @Test
    void exhaustedDeadlockBudgetReturnsConflictWithoutPartialWrites() throws Exception {
        Session alice = login("alice");
        installFailure("PERFORM nextval('slotsync_test_retry'); RAISE EXCEPTION 'synthetic deadlock' USING ERRCODE='40P01';");
        try {
            MvcResult result = create(alice, "exhausted-request", body(ROOM, future(), future().plusSeconds(3600)));
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(parse(result).get("code").asText()).isEqualTo("BOOKING_BUSY");
            assertThat(jdbc.queryForObject("SELECT last_value FROM slotsync_test_retry", Integer.class)).isEqualTo(3);
            assertNoWrites();
        } finally { removeFailure(); }
    }

    @Test
    void disabledAndChangedRoleAccountsCannotKeepWritingThroughOldSession() throws Exception {
        Session alice = login("alice");
        jdbc.update("UPDATE users SET enabled=false WHERE id=?", ALICE);
        assertThat(create(alice, "disabled-request", body(ROOM, future(), future().plusSeconds(3600))).getResponse().getStatus()).isEqualTo(401);
        jdbc.update("UPDATE users SET enabled=true,role='ADMIN' WHERE id=?", ALICE);
        assertThat(create(alice, "changed-role-request", body(ROOM, future(), future().plusSeconds(3600))).getResponse().getStatus()).isEqualTo(403);
        assertNoWrites();
    }

    private Session login(String username) throws Exception {
        MvcResult anonymous = mvc.perform(get("/api/session")).andReturn();
        JsonNode token = parse(anonymous);
        MvcResult login = mvc.perform(post("/api/login").session((MockHttpSession) anonymous.getRequest().getSession(false))
                .header(token.get("csrfHeader").asText(), token.get("csrfToken").asText())
                .param("username", username).param("password", PASSWORD)).andExpect(status().isNoContent()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        JsonNode current = parse(mvc.perform(get("/api/session").session(session)).andReturn());
        return new Session(session, current.get("csrfHeader").asText(), current.get("csrfToken").asText());
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, Session session) {
        return request.session(session.session).header(session.header, session.token);
    }

    private MvcResult create(Session session, String key, String payload) throws Exception {
        return mvc.perform(authorized(post("/api/bookings"), session).header("Idempotency-Key", key)
                .contentType("application/json").content(payload)).andReturn();
    }

    private JsonNode parse(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private Instant future() { return Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS); }
    private String body(String resource, Instant start, Instant end) throws Exception {
        return json.writeValueAsString(Map.of("resourceId", resource, "startsAt", start.toString(), "endsAt", end.toString()));
    }
    private int count(String table) {
        if (!List.of("bookings", "audit_events", "idempotency_requests").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    private void assertNoWrites() {
        assertThat(count("bookings")).isZero(); assertThat(count("audit_events")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    private void installFailure(String statement) {
        jdbc.execute("CREATE SEQUENCE slotsync_test_retry");
        jdbc.execute("CREATE FUNCTION slotsync_test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + statement + " RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER slotsync_test_fail_audit BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION slotsync_test_fail_audit()");
    }
    private void removeFailure() {
        jdbc.execute("DROP TRIGGER slotsync_test_fail_audit ON audit_events");
        jdbc.execute("DROP FUNCTION slotsync_test_fail_audit()");
        jdbc.execute("DROP SEQUENCE slotsync_test_retry");
    }
    private record Session(MockHttpSession session, String header, String token) {}
}
