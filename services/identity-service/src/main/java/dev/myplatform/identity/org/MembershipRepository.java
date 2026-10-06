package dev.myplatform.identity.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    /** The org a fresh login starts in: the one the user joined first. */
    Optional<Membership> findFirstByIdUserIdOrderByCreatedAtAsc(UUID userId);

    /** All orgs of a user with their role in each, oldest membership first (org switcher). */
    @Query("""
            SELECT new dev.myplatform.identity.org.OrgResponse(o.id, o.name, o.slug, m.role)
            FROM Membership m JOIN Organization o ON o.id = m.id.orgId
            WHERE m.id.userId = :userId
            ORDER BY m.createdAt
            """)
    List<OrgResponse> findOrgsOfUser(UUID userId);

}
