package dev.myplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The V1 migration applies on a real Postgres and its constraints enforce the identity rules. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // each test rolls back
class SchemaMigrationTests {

    @Autowired
    JdbcClient db;

    @Test
    void migrationIsApplied() {
        String version = db.sql("SELECT max(version) FROM flyway_schema_history WHERE success")
                .query(String.class).single();
        assertThat(version).isEqualTo("2");
    }

    // One expected failure per test: Postgres aborts the transaction after the first error.
    @Test
    void emailsAreUnique() {
        insertUser("ada@example.com");
        assertThatThrownBy(() -> insertUser("ada@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailsMustBeLowerCase() {
        assertThatThrownBy(() -> insertUser("Grace@Example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void membershipRoleIsRestricted() {
        UUID userId = insertUser("ada@example.com");
        UUID orgId = insertOrg("acme");

        insertMembership(orgId, userId, "OWNER");

        UUID otherUser = insertUser("grace@example.com");
        assertThatThrownBy(() -> insertMembership(orgId, otherUser, "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void slugMustBeUrlSafe() {
        insertOrg("acme-inc");
        assertThatThrownBy(() -> insertOrg("Acme Inc"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserRemovesMembershipsAndRefreshTokens() {
        UUID userId = insertUser("ada@example.com");
        UUID orgId = insertOrg("acme");
        insertMembership(orgId, userId, "OWNER");
        db.sql("""
                INSERT INTO refresh_tokens (user_id, org_id, token_hash, family_id, expires_at)
                VALUES (?, ?, sha256('opaque-token'::bytea), uuidv7(), now() + interval '7 days')
                """).params(userId, orgId).update();

        db.sql("DELETE FROM users WHERE id = ?").param(userId).update();

        assertThat(count("memberships")).isZero();
        assertThat(count("refresh_tokens")).isZero();
        assertThat(count("organizations")).isEqualTo(1);
    }

    private UUID insertUser(String email) {
        return db.sql("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'x', 'Test') RETURNING id")
                .param(email).query(UUID.class).single();
    }

    private UUID insertOrg(String slug) {
        return db.sql("INSERT INTO organizations (name, slug) VALUES ('Test org', ?) RETURNING id")
                .param(slug).query(UUID.class).single();
    }

    private void insertMembership(UUID orgId, UUID userId, String role) {
        db.sql("INSERT INTO memberships (org_id, user_id, role) VALUES (?, ?, ?)")
                .params(orgId, userId, role).update();
    }

    private long count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

}
