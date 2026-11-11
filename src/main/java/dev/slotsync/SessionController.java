package dev.slotsync;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    @GetMapping("/api/session")
    public ResponseEntity<SessionView> session(Authentication authentication, CsrfToken csrf) {
        SlotUser user = authentication != null && authentication.getPrincipal() instanceof SlotUser principal ? principal : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionView(
                user == null ? null : user.getUsername(),
                user == null ? null : user.getAuthorities().iterator().next().getAuthority().substring(5),
                csrf.getToken(), csrf.getHeaderName()));
    }

    public record SessionView(String username, String role, String csrfToken, String csrfHeader) {}
}
