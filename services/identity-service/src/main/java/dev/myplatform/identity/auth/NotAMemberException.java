package dev.myplatform.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Also used for orgs that don't exist, so the response doesn't reveal which org ids are real. */
class NotAMemberException extends ErrorResponseException {

    NotAMemberException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not a member of this organization"), null);
    }

}
