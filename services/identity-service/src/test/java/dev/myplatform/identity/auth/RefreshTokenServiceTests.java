package dev.myplatform.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Rotation and reuse rules (ADR 0003) without a database: mocked repository, fixed clock. */
class RefreshTokenServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(repository,
            new RefreshTokenProperties(TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID userId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();

    @Test
    void tokensAre256RandomBitsInBase64Url() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(RefreshTokenService.generate());
        }
        assertThat(tokens).hasSize(100).allSatisfy(token -> assertThat(token).matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void hashIsSha256AndDeterministic() {
        String token = RefreshTokenService.generate();
        assertThat(RefreshTokenService.hash(token)).hasSize(32).isEqualTo(RefreshTokenService.hash(token));
        assertThat(RefreshTokenService.hash(token)).isNotEqualTo(RefreshTokenService.hash(token + "x"));
    }

    @Test
    void newFamilyStoresOnlyTheHashWithTheTtl() {
        UUID orgId = UUID.randomUUID();
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);

        String token = service.issueNewFamily(userId, orgId);

        verify(repository).save(saved.capture());
        RefreshToken row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getOrgId()).isEqualTo(orgId);
        assertThat(ReflectionTestUtils.getField(row, "tokenHash")).isEqualTo(RefreshTokenService.hash(token));
        assertThat(ReflectionTestUtils.getField(row, "expiresAt")).isEqualTo(NOW.plus(TTL));
        assertThat(row.isRevoked()).isFalse();
    }

    @Test
    void consumeReturnsAValidToken() {
        RefreshToken stored = storedToken("valid", NOW.plusSeconds(1));

        assertThat(service.consume("valid")).isSameAs(stored);
        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume("unknown")).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredTokenIsRejectedWithoutRevokingTheFamily() {
        storedToken("expired", NOW); // expires exactly now: no longer valid

        assertThatThrownBy(() -> service.consume("expired")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void reusedTokenRevokesItsWholeFamily() {
        RefreshToken stored = storedToken("rotated", NOW.plus(TTL));
        stored.replaceWith(withId(new RefreshToken(userId, null, new byte[32], familyId, NOW.plus(TTL))),
                NOW.minusSeconds(60));
        when(repository.revokeFamily(familyId, NOW)).thenReturn(1);

        assertThatThrownBy(() -> service.consume("rotated")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository).revokeFamily(familyId, NOW);
    }

    @Test
    void rotateRevokesTheTokenAndIssuesASuccessorInTheSameFamily() {
        RefreshToken current = storedToken("current", NOW.plusSeconds(10));
        UUID newOrg = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(call -> {
            RefreshToken successor = call.getArgument(0);
            ReflectionTestUtils.setField(successor, "id", successorId); // what Hibernate's generator does
            return successor;
        });

        String next = service.rotate(current, newOrg);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        RefreshToken successor = saved.getValue();
        assertThat(successor.getFamilyId()).isEqualTo(familyId);
        assertThat(successor.getOrgId()).isEqualTo(newOrg);
        assertThat(ReflectionTestUtils.getField(successor, "tokenHash")).isEqualTo(RefreshTokenService.hash(next));
        // Sliding expiry: a full TTL from now, not the remaining 10 seconds.
        assertThat(ReflectionTestUtils.getField(successor, "expiresAt")).isEqualTo(NOW.plus(TTL));
        assertThat(current.isRevoked()).isTrue();
        assertThat(ReflectionTestUtils.getField(current, "replacedBy")).isEqualTo(successorId);
    }

    @Test
    void logoutOfAnUnknownTokenDoesNothing() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revokeFamilyOf("unknown");

        verify(repository, never()).revokeFamily(any(), any());
    }

    private RefreshToken storedToken(String token, Instant expiresAt) {
        RefreshToken stored = withId(new RefreshToken(userId, null, RefreshTokenService.hash(token), familyId,
                expiresAt));
        when(repository.findByTokenHash(any())).thenAnswer(call ->
                Arrays.equals(call.getArgument(0), RefreshTokenService.hash(token))
                        ? Optional.of(stored) : Optional.empty());
        return stored;
    }

    private static RefreshToken withId(RefreshToken token) {
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

}
