package dev.myplatform.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Same response for an unknown email and a wrong password, so login can't be used to probe accounts. */
class InvalidCredentialsException extends ErrorResponseException {

    InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password"), null);
    }

}
