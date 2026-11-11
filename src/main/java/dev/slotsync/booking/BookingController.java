package dev.slotsync.booking;

import dev.slotsync.SlotUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookings;
    public BookingController(BookingService bookings) { this.bookings = bookings; }

    @PostMapping
    public ResponseEntity<BookingService.BookingView> create(@AuthenticationPrincipal SlotUser user,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @Valid @RequestBody BookingRequest request) {
        var result = bookings.create(user.userId(), key, request.resourceId(), request.startsAt().toInstant(), request.endsAt().toInstant());
        return ResponseEntity.status(result.replayed() ? 200 : 201).cacheControl(CacheControl.noStore())
                .header("Idempotent-Replayed", Boolean.toString(result.replayed())).body(result.booking());
    }

    @GetMapping
    public ResponseEntity<List<BookingService.BookingView>> list(@AuthenticationPrincipal SlotUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bookings.list(user.userId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingService.BookingView> get(@AuthenticationPrincipal SlotUser user, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bookings.get(user.userId(), id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingService.BookingView> cancel(@AuthenticationPrincipal SlotUser user, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bookings.cancel(user.userId(), id));
    }

    public record BookingRequest(@NotNull UUID resourceId, @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) {}
}
