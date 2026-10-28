package org.archive.sitearchive.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Maps every error to an RFC 9457 problem detail. Feature exceptions extend {@code ErrorResponseException} and
 * carry their own status and body, which the base class renders; this class only adds a consistent format for
 * framework validation errors and a safe fallback for unexpected ones.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(e, ProblemDetails.validation(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<ValidationError> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(fieldName(error, result.getMethodParameter()),
                                error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(e, ProblemDetails.validation(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = e.getPropertyName() != null ? e.getPropertyName() : "request";
        String expected = e.getRequiredType() == null ? "a valid value" : describe(e.getRequiredType());
        ValidationError error = new ValidationError(field, "must be " + expected);
        return handleExceptionInternal(e, ProblemDetails.validation(List.of(error)), headers, HttpStatus.BAD_REQUEST, request);
    }

    private static String fieldName(MessageSourceResolvable error, MethodParameter parameter) {
        return error instanceof FieldError fieldError ? fieldError.getField() : parameter.getParameterName();
    }

    private static String describe(Class<?> type) {
        if (type.isEnum()) {
            return "one of " + List.of(type.getEnumConstants());
        }
        return "a valid " + type.getSimpleName();
    }
}
