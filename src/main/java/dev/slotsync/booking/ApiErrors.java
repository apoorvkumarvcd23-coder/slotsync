package dev.slotsync.booking;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BookingService.BookingError.class)
    ResponseEntity<ErrorView> booking(BookingService.BookingError error) {
        return ResponseEntity.status(error.status).body(new ErrorView(error.code, error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorView> storage(DataAccessException error) {
        return ResponseEntity.status(503).body(new ErrorView("STORAGE_UNAVAILABLE", "Storage is temporarily unavailable."));
    }

    public record ErrorView(String code, String message) {}
}
