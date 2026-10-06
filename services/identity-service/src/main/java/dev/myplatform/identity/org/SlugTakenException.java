package dev.myplatform.identity.org;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

class SlugTakenException extends ErrorResponseException {

    SlugTakenException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Organization slug is already taken"), null);
    }

}
