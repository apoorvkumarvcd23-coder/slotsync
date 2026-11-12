package dev.slotsync.booking;
import dev.slotsync.SlotUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final BookingService bookings;
    public ApprovalController(BookingService bookings) {this.bookings=bookings;}
    @GetMapping
    public ResponseEntity<List<BookingService.ApprovalView>> queue(@AuthenticationPrincipal SlotUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bookings.approvals(user.userId()));
    }
    @PostMapping("/{id}/decision")
    public ResponseEntity<BookingService.BookingView> decide(@AuthenticationPrincipal SlotUser user,@PathVariable UUID id,@Valid @RequestBody DecisionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bookings.decide(user.userId(),id,request.decision().name()));
    }
    public enum Decision { APPROVED, REJECTED }
    public record DecisionRequest(@NotNull Decision decision) {}
}
