package dev.myplatform.identity.org;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    /** The org a fresh login starts in: the one the user joined first. */
    Optional<Membership> findFirstByIdUserIdOrderByCreatedAtAsc(UUID userId);

}
