package dev.slotsync.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public BookingService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    public CreateResult create(UUID actor, String key, UUID resource, Instant start, Instant end) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}")) {
            throw error(400, "INVALID_REQUEST_KEY", "Use an 8–100 character request key.");
        }
        Instant starts = start.truncatedTo(ChronoUnit.MICROS);
        Instant ends = end.truncatedTo(ChronoUnit.MICROS);
        String hash = hash(resource + "|" + starts + "|" + ends);
        return atomic(() -> {
            lockActor(actor);
            var replays = jdbc.queryForList("SELECT request_hash,response_json FROM idempotency_requests WHERE actor_id=? AND request_key=?", actor, key);
            if (!replays.isEmpty()) {
                var previous = replays.getFirst();
                if (!previous.get("request_hash").equals(hash)) throw error(409, "REQUEST_KEY_REUSED", "This request key has a different payload.");
                try {
                    return new CreateResult(json.readValue((String) previous.get("response_json"), BookingView.class), true);
                } catch (JsonProcessingException failure) {
                    throw new IllegalStateException("Stored booking response is invalid", failure);
                }
            }
            Instant now = Instant.now();
            if (!starts.isBefore(ends) || starts.isBefore(now) || starts.isAfter(now.plus(365, ChronoUnit.DAYS))
                    || Duration.between(starts, ends).compareTo(Duration.ofHours(24)) > 0) {
                throw error(400, "INVALID_INTERVAL", "Choose a future interval within one year, lasting at most 24 hours.");
            }
            lockResource(resource);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO bookings(id,resource_id,owner_id,starts_at,ends_at,status) VALUES (?,?,?,?,?,'PENDING')",
                    id, resource, actor, OffsetDateTime.ofInstant(starts, ZoneOffset.UTC), OffsetDateTime.ofInstant(ends, ZoneOffset.UTC));
            audit(actor, id, "CREATED", null, "PENDING");
            BookingView result = owned(actor, id);
            try {
                jdbc.update("INSERT INTO idempotency_requests VALUES (?,?,?,?,?)", actor, key, hash, id, json.writeValueAsString(result));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Could not encode booking response", failure);
            }
            return new CreateResult(result, false);
        });
    }

    public List<BookingView> list(UUID actor) {
        return atomic(() -> {
            lockActor(actor);
            return jdbc.query(SELECT + " WHERE b.owner_id=? ORDER BY b.starts_at DESC,b.id LIMIT 100", BookingService::view, actor);
        });
    }

    public BookingView get(UUID actor, UUID id) {
        return atomic(() -> { lockActor(actor); return owned(actor, id); });
    }

    public BookingView cancel(UUID actor, UUID id) {
        return atomic(() -> {
            lockActor(actor);
            BookingView preliminary = owned(actor, id);
            // All writes use actor -> resource -> booking lock order, across app instances.
            jdbc.queryForList("SELECT id FROM resources WHERE id=? FOR UPDATE", preliminary.resourceId());
            jdbc.queryForList("SELECT id FROM bookings WHERE id=? FOR UPDATE", id);
            BookingView booking = owned(actor, id);
            if (booking.status().equals("CANCELLED")) return booking;
            if (!List.of("PENDING", "APPROVED").contains(booking.status())) throw error(409, "INVALID_TRANSITION", "This booking cannot be cancelled.");
            jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=?", id);
            audit(actor, id, "CANCELLED", booking.status(), "CANCELLED");
            return owned(actor, id);
        });
    }

    private void lockActor(UUID actor) {
        var users = jdbc.queryForList("SELECT role,enabled FROM users WHERE id=? FOR UPDATE", actor);
        if (users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled"))) throw error(401, "LOGIN_REQUIRED", "Sign in again.");
        if (!users.getFirst().get("role").equals("REQUESTER")) throw error(403, "REQUESTER_REQUIRED", "A requester account is required.");
    }

    private void lockResource(UUID id) {
        var rows = jdbc.queryForList("SELECT active FROM resources WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("active"))) throw error(404, "RESOURCE_NOT_FOUND", "Resource is not available.");
    }

    private BookingView owned(UUID actor, UUID id) {
        return jdbc.query(SELECT + " WHERE b.owner_id=? AND b.id=?", BookingService::view, actor, id).stream()
                .findFirst().orElseThrow(() -> error(404, "BOOKING_NOT_FOUND", "Booking not found."));
    }

    private void audit(UUID actor, UUID booking, String action, String oldStatus, String status) {
        jdbc.update("INSERT INTO audit_events(id,actor_id,booking_id,action,previous_status,new_status) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), actor, booking, action, oldStatus, status);
    }

    private <T> T atomic(Supplier<T> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (DataAccessException failure) {
                String state = sqlState(failure);
                if ("23P01".equals(state)) throw error(409, "SLOT_CONFLICT", "This resource already has a booking in that interval.");
                if (!List.of("40P01", "40001").contains(state == null ? "" : state)) throw failure;
                if (attempt == 2) throw error(409, "BOOKING_BUSY", "Retry with the same request key.");
            }
        }
        throw new IllegalStateException("Unreachable transaction state");
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static BookingView view(java.sql.ResultSet rs, int row) throws SQLException {
        return new BookingView(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getString("display_name"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(), rs.getString("status"));
    }

    private static BookingError error(int status, String code, String message) { return new BookingError(status, code, message); }
    private static final String SELECT = "SELECT b.*,r.display_name FROM bookings b JOIN resources r ON r.id=b.resource_id";
    public record BookingView(UUID id, UUID resourceId, String resourceName, Instant startsAt, Instant endsAt, String status) {}
    public record CreateResult(BookingView booking, boolean replayed) {}
    public static class BookingError extends RuntimeException {
        public final int status;
        public final String code;
        BookingError(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
