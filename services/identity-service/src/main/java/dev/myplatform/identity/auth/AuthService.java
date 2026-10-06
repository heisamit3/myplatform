package dev.myplatform.identity.auth;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.myplatform.identity.org.Membership;
import dev.myplatform.identity.org.MembershipId;
import dev.myplatform.identity.org.MembershipRepository;
import dev.myplatform.identity.token.AccessTokenIssuer;
import dev.myplatform.identity.token.JwtProperties;
import dev.myplatform.identity.user.Emails;
import dev.myplatform.identity.user.User;
import dev.myplatform.identity.user.UserRegistered;
import dev.myplatform.identity.user.UserRepository;

@Service
class AuthService {

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final long accessTokenTtlSeconds;
    // Checked when the email is unknown, so both paths cost one bcrypt and take about the same time.
    private final String dummyHash;

    AuthService(UserRepository users, MembershipRepository memberships, RefreshTokenService refreshTokens,
            AccessTokenIssuer accessTokens, PasswordEncoder passwordEncoder, JwtProperties jwtProperties,
            ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.accessTokenTtlSeconds = jwtProperties.accessTokenTtl().toSeconds();
        this.dummyHash = passwordEncoder.encode(RefreshTokenService.generate());
    }

    // Transactional: the user row and its outbox event (written by a synchronous listener) commit together.
    @Transactional
    UserResponse register(RegisterRequest request) {
        String email = Emails.normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.displayName());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations passed the check above; the UNIQUE constraint decides.
            throw new EmailAlreadyRegisteredException();
        }
        // Milliseconds: what JavaScript Dates (notification-service) can represent.
        events.publishEvent(UserRegistered.of(user, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        return UserResponse.from(user);
    }

    @Transactional
    TokenResponse login(LoginRequest request) {
        Optional<User> user = users.findByEmail(Emails.normalize(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = Passwords.fitsBcrypt(request.password())
                && passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        UUID userId = user.get().getId();
        return issueTokens(userId, activeMembership(userId, null),
                orgId -> refreshTokens.issueNewFamily(userId, orgId));
    }

    // noRollbackFor: a reused token revokes its family and then fails; that revocation must still commit.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    TokenResponse refresh(RefreshTokenRequest request) {
        RefreshToken current = refreshTokens.consume(request.refreshToken());
        UUID userId = current.getUserId();
        // Roles are re-read on every refresh, so a role change reaches tokens within one access-token TTL.
        return issueTokens(userId, activeMembership(userId, current.getOrgId()),
                orgId -> refreshTokens.rotate(current, orgId));
    }

    // A non-member rolls back without changes: the presented refresh token stays valid.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    TokenResponse switchOrg(SwitchOrgRequest request) {
        RefreshToken current = refreshTokens.consume(request.refreshToken());
        UUID userId = current.getUserId();
        Membership membership = memberships.findById(new MembershipId(request.orgId(), userId))
                .orElseThrow(NotAMemberException::new);
        return issueTokens(userId, Optional.of(membership), orgId -> refreshTokens.rotate(current, orgId));
    }

    @Transactional
    void logout(RefreshTokenRequest request) {
        refreshTokens.revokeFamilyOf(request.refreshToken());
    }

    private Optional<Membership> activeMembership(UUID userId, @Nullable UUID preferredOrgId) {
        if (preferredOrgId != null) {
            Optional<Membership> preferred = memberships.findById(new MembershipId(preferredOrgId, userId));
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        // No org chosen yet, or the user was removed from it: start in the first org they joined.
        return memberships.findFirstByIdUserIdOrderByCreatedAtAsc(userId);
    }

    private TokenResponse issueTokens(UUID userId, Optional<Membership> membership,
            Function<@Nullable UUID, String> refreshTokenForOrg) {
        @Nullable UUID orgId = membership.map(m -> m.getId().orgId()).orElse(null);
        List<String> roles = membership.map(m -> List.of(m.getRole().name())).orElse(List.of());
        return new TokenResponse(
                accessTokens.issue(userId, orgId, roles),
                "Bearer",
                accessTokenTtlSeconds,
                refreshTokenForOrg.apply(orgId));
    }

}
