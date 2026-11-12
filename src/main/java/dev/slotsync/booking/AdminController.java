package dev.slotsync.booking;

import dev.slotsync.SlotUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin) {this.admin=admin;}
    private static <T> ResponseEntity<T> response(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/api/policy")
    public ResponseEntity<AdminService.Policy> policy() {return response(admin.currentPolicy());}
    @GetMapping("/api/admin/policy")
    public ResponseEntity<AdminService.Policy> policy(@AuthenticationPrincipal SlotUser user) {return response(admin.adminPolicy(user.userId()));}
    @PostMapping("/api/admin/policy")
    public ResponseEntity<AdminService.Policy> save(@AuthenticationPrincipal SlotUser user,@Valid @RequestBody PolicyRequest request) {
        return response(admin.savePolicy(user.userId(),request.expectedVersion(),request.maxDurationMinutes(),request.approvalRequired()));
    }
    @GetMapping("/api/admin/imports")
    public ResponseEntity<List<AdminService.Batch>> imports(@AuthenticationPrincipal SlotUser user) {return response(admin.imports(user.userId()));}
    @PostMapping("/api/admin/imports/preview")
    public ResponseEntity<AdminService.Batch> preview(@AuthenticationPrincipal SlotUser user,@Valid @RequestBody CsvRequest request) {return response(admin.preview(user.userId(),request.csv()));}
    @PostMapping("/api/admin/imports/{id}/apply")
    public ResponseEntity<AdminService.Batch> apply(@AuthenticationPrincipal SlotUser user,@PathVariable UUID id) {return response(admin.apply(user.userId(),id));}
    @GetMapping("/api/admin/audit")
    public ResponseEntity<List<AdminService.AuditView>> audit(@AuthenticationPrincipal SlotUser user) {return response(admin.audit(user.userId()));}
    public record PolicyRequest(@NotNull @Min(1) Long expectedVersion,@NotNull @Min(1) @Max(1440) Integer maxDurationMinutes,@NotNull Boolean approvalRequired) {}
    public record CsvRequest(@NotNull @Size(max=65536) String csv) {}
}
