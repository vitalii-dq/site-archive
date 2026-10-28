package org.archive.sitearchive.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

/** Builds the problem details used for every error response. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    /** All validation failures share this format: status 400 with an {@code errors} list of {@code {field, message}}. */
    public static ProblemDetail validation(List<ValidationError> errors) {
        ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "Validation failed", "The request contains invalid values");
        problem.setProperty("errors", errors);
        return problem;
    }
}
