package dev.myplatform.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring MVC as an RFC 9457 problem+json response. */
class EmailAlreadyRegisteredException extends ErrorResponseException {

    EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email is already registered"), null);
    }

}
