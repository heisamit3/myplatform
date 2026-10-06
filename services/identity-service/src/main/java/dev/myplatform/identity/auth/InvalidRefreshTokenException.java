package dev.myplatform.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** One response for unknown, expired, revoked and reused tokens: the client just logs in again. */
class InvalidRefreshTokenException extends ErrorResponseException {

    InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid refresh token"), null);
    }

}
