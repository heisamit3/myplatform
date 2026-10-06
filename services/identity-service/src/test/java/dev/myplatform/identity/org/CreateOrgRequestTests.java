package dev.myplatform.identity.org;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The slug rule must match the CHECK on organizations.slug, or valid requests would fail in the database. */
class CreateOrgRequestTests {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"acme", "acme-inc", "a1-b2-c3", "0"})
    void acceptsUrlSafeSlugs(String slug) {
        assertThat(validator.validate(new CreateOrgRequest("Acme", slug))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Acme", "acme inc", "-acme", "acme-", "acme--inc", "acme_inc", "ácme", ""})
    void rejectsOtherSlugs(String slug) {
        assertThat(validator.validate(new CreateOrgRequest("Acme", slug))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {63, 64})
    void slugIsAtMostOneDnsLabelLong(int length) {
        boolean valid = validator.validate(new CreateOrgRequest("Acme", "a".repeat(length))).isEmpty();
        assertThat(valid).isEqualTo(length <= 63);
    }

    @Test
    void nameIsTrimmedAndRequired() {
        assertThat(new CreateOrgRequest("  Acme  ", "acme").name()).isEqualTo("Acme");
        assertThat(validator.validate(new CreateOrgRequest("   ", "acme"))).isNotEmpty();
    }

}
