package dev.slotsync;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "slotsync.seed", name = "enabled", havingValue = "true")
public class SeedUsers implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String[] passwords;

    public SeedUsers(JdbcTemplate jdbc, PasswordEncoder encoder,
                     @Value("${slotsync.seed.requester-password:}") String requester,
                     @Value("${slotsync.seed.requester2-password:}") String requester2,
                     @Value("${slotsync.seed.approver-password:}") String approver,
                     @Value("${slotsync.seed.admin-password:}") String admin) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.passwords = new String[] {requester, requester2, approver, admin};
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String[] usernames = {"requester", "requester2", "approver", "admin"};
        String[] roles = {"REQUESTER", "REQUESTER", "APPROVER", "ADMIN"};
        for (int i = 0; i < usernames.length; i++) {
            if (passwords[i].length() < 24 || passwords[i].length() > 64) {
                throw new IllegalStateException("Generate the ignored demo credentials before enabling seeded users");
            }
            jdbc.update("INSERT INTO users(id, username, password_hash, role) VALUES (?,?,?,?) ON CONFLICT(username) DO NOTHING",
                    UUID.nameUUIDFromBytes(("slotsync-demo-" + usernames[i]).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    usernames[i], encoder.encode(passwords[i]), roles[i]);
        }
    }
}
