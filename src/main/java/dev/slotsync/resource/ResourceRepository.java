package dev.slotsync.resource;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {
    List<Resource> findByActiveTrueOrderByCodeAsc();
}
