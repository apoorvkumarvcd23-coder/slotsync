package dev.slotsync.resource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "resources")
public class Resource {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String code;
    @Column(name = "display_name", nullable = false) private String name;
    @Column(name = "type_code", nullable = false) private String type;
    @Column(nullable = false) private String location;
    @Column(nullable = false) private int capacity;
    @Column(nullable = false) private boolean active;

    protected Resource() {}

    public ResourceView view() {
        return new ResourceView(id, code, name, type, location, capacity);
    }

    public record ResourceView(UUID id, String code, String name, String type, String location, int capacity) {}
}
