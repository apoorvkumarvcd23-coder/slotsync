package dev.slotsync.resource;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResourceController {
    private final ResourceRepository resources;

    public ResourceController(ResourceRepository resources) {
        this.resources = resources;
    }

    @GetMapping("/api/resources")
    public ResponseEntity<List<Resource.ResourceView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                resources.findByActiveTrueOrderByCodeAsc().stream().map(Resource::view).toList());
    }
}
