package dev.slotsync.booking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyScheduleTest {
    @Test void horizonIncludesExactElapsedDayBoundaryButNotTheNextMicrosecond() {
        var rule=new PolicySchedule(7,List.of(1,2,3,4,5,6,7),"00:00","24:00");
        Instant now=Instant.parse("2026-11-19T12:00:00Z");
        Instant boundary=Instant.parse("2026-11-26T12:00:00Z");
        rule.check(boundary,boundary.plusSeconds(3600),now);
        assertThatThrownBy(()->rule.check(boundary.plusNanos(1000),boundary.plusSeconds(3600),now))
                .isInstanceOf(BookingService.BookingError.class).hasMessageContaining("horizon");
    }
}
