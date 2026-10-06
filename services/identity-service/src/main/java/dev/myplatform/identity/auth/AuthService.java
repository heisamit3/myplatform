package dev.myplatform.identity.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.myplatform.identity.org.Membership;
import dev.myplatform.identity.org.MembershipRepository;
import dev.myplatform.identity.token.AccessTokenIssuer;
import dev.myplatform.identity.token.JwtProperties;
import dev.myplatform.identity.user.Emails;
import dev.myplatform.identity.user.User;
import dev.myplatform.identity.user.UserRepository;

@Service
class AuthService {

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final long accessTokenTtlSeconds;
    // Checked when the email is unknown, so both paths cost one bcrypt and take about the same time.
    private final String dummyHash;

    AuthService(UserRepository users, MembershipRepository memberships, RefreshTokenService refreshTokens,
            AccessTokenIssuer accessTokens, PasswordEncoder passwordEncoder, JwtProperties jwtProperties) {
        this.users = users;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenTtlSeconds = jwtProperties.accessTokenTtl().toSeconds();
        this.dummyHash = passwordEncoder.encode(RefreshTokenService.generate());
    }

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
        Optional<Membership> activeMembership = memberships.findFirstByIdUserIdOrderByCreatedAtAsc(userId);
        @Nullable UUID orgId = activeMembership.map(m -> m.getId().orgId()).orElse(null);
        List<String> roles = activeMembership.map(m -> List.of(m.getRole().name())).orElse(List.of());

        return new TokenResponse(
                accessTokens.issue(userId, orgId, roles),
                "Bearer",
                accessTokenTtlSeconds,
                refreshTokens.issueNewFamily(userId, orgId));
    }

}
