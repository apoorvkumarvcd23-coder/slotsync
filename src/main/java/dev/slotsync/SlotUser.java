package dev.slotsync;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class SlotUser extends User {
    private final UUID userId;

    public SlotUser(UUID userId, String username, String hash, String role, boolean enabled) {
        super(username, hash, enabled, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.userId = userId;
    }

    public UUID userId() { return userId; }
}
