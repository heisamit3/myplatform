package dev.myplatform.identity.auth;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
}
