package com.redditclone.common.exception;

import com.redditclone.common.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Object> handleConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorized(UnauthorizedException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Object> handleForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Object> handleBadRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Object> handleTooManyRequests(TooManyRequestsException ex) {
        return body(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    // Safety net behind the service-layer existence pre-checks (email/username in AuthService,
    // community name in CommunityService): two concurrent requests can both pass a pre-check before
    // either commits, so the DB's UNIQUE constraint is still the actual source of truth. Without this,
    // that race surfaces as a raw 500 instead of the same 409 the pre-check gives the common case.
    //
    // DataIntegrityViolationException also covers CHECK/NOT-NULL/FK violations, which are not
    // conflicts — e.g. a comment body that expands past the 10000-char CHECK constraint after HTML
    // sanitization. Only Postgres's unique_violation SQLState (23505) maps to 409; everything else
    // is a client-input problem this app has no other way to have caused, so it maps to 400.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
            return body(HttpStatus.CONFLICT, "resource already exists");
        }
        return body(HttpStatus.BAD_REQUEST, "invalid request");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "this changed while you were editing it — reload and try again");
    }

    private ResponseEntity<Object> body(HttpStatus status, String message) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                // A concrete reference id the client/user can quote when reporting a bug — see
                // CorrelationIdFilter. Falls back to "" the same way message does, for a caller outside
                // the filter's reach (e.g. a direct unit test of this handler).
                "correlationId", correlationId == null ? "" : correlationId
        ));
    }
}
