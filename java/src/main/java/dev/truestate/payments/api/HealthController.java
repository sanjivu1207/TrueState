package dev.truestate.payments.api;

import dev.truestate.payments.api.Dtos.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Liveness + DB connectivity")
    @GetMapping("/health")
    public HealthResponse health() {
        String db;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            db = "ok";
        } catch (DataAccessException e) {
            db = "unavailable";
        }
        return new HealthResponse("ok", db);
    }
}
