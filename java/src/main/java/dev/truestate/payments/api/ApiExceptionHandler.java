package dev.truestate.payments.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.truestate.payments.api.Dtos.ErrorResponse;
import dev.truestate.payments.payments.ApiError;
import jakarta.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Errors in the same shapes FastAPI produces, so clients can't tell the two
 * services apart:
 * <ul>
 *   <li>business errors: {@code {"detail": "account not found"}}
 *   <li>validation errors (422): {@code {"detail": [{"type", "loc", "msg", "input"}, ...]}}
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE = new PropertyNamingStrategies.SnakeCaseStrategy();

    @ExceptionHandler(ApiError.class)
    ResponseEntity<ErrorResponse> apiError(ApiError e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.detail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.add(fieldError(fe));
        }
        return unprocessable(errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return unprocessable(List.of(error("missing", List.of("header", e.getHeaderName()), "Field required", null)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> badPathParam(MethodArgumentTypeMismatchException e) {
        String type = e.getRequiredType() == UUID.class ? "uuid_parsing" : "type_error";
        String msg = e.getRequiredType() == UUID.class ? "Input should be a valid UUID" : "Invalid value";
        return unprocessable(List.of(error(type, List.of("path", SNAKE.translate(e.getName())), msg, e.getValue())));
    }

    /** Malformed JSON, missing body, or a value of the wrong type (e.g. a non-UUID id). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            List<Object> loc = new ArrayList<>();
            loc.add("body");
            jme.getPath().forEach(ref -> loc.add(ref.getFieldName() != null ? ref.getFieldName() : ref.getIndex()));
            return unprocessable(List.of(error("value_error", loc, "Input is not valid for this field", null)));
        }
        if (e.getMessage() != null && e.getMessage().startsWith("Required request body is missing")) {
            return unprocessable(List.of(error("missing", List.of("body"), "Field required", null)));
        }
        return unprocessable(List.of(error("json_invalid", List.of("body"), "JSON decode error", null)));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorResponse> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Not Found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ErrorResponse("Method Not Allowed"));
    }

    /**
     * Anything unexpected: 500, and crucially the idempotency key is NOT
     * marked completed (the payment transaction rolled back), so a retry can
     * safely try again instead of replaying a response that never existed.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Internal Server Error"));
    }

    private static Map<String, Object> fieldError(FieldError fe) {
        List<Object> loc = List.of("body", SNAKE.translate(fe.getField()));
        Object input = fe.getRejectedValue();
        Map<String, Object> attrs = constraintAttributes(fe);
        return switch (String.valueOf(fe.getCode())) {
            case "NotNull" -> error("missing", loc, "Field required", null);
            case "Positive" -> withCtx(
                    error("greater_than", loc, "Input should be greater than 0", input), Map.of("gt", 0));
            case "PositiveOrZero" -> withCtx(
                    error("greater_than_equal", loc, "Input should be greater than or equal to 0", input),
                    Map.of("ge", 0));
            case "Size" -> {
                int min = (Integer) attrs.get("min");
                int max = (Integer) attrs.get("max");
                yield input instanceof String s && s.length() < min
                        ? withCtx(
                                error("string_too_short", loc, "String should have at least " + plural(min, "character"), input),
                                Map.of("min_length", min))
                        : withCtx(
                                error("string_too_long", loc, "String should have at most " + plural(max, "character"), input),
                                Map.of("max_length", max));
            }
            default -> error("value_error", loc, String.valueOf(fe.getDefaultMessage()), input);
        };
    }

    private static Map<String, Object> withCtx(Map<String, Object> error, Map<String, Object> ctx) {
        error.put("ctx", ctx);
        return error;
    }

    private static Map<String, Object> constraintAttributes(FieldError fe) {
        try {
            return fe.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAttributes();
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static Map<String, Object> error(String type, List<?> loc, String msg, Object input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("loc", loc);
        m.put("msg", msg);
        m.put("input", input);
        return m;
    }

    private static ResponseEntity<ErrorResponse> unprocessable(List<Map<String, Object>> errors) {
        return ResponseEntity.unprocessableEntity().body(new ErrorResponse(errors));
    }
}
