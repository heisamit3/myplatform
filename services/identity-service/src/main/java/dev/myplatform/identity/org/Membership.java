package dev.myplatform.identity.org;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "memberships")
public class Membership {

    @EmbeddedId
    private MembershipId id;

    @Enumerated(EnumType.STRING)
    private Role role;

    @CreationTimestamp
    private Instant createdAt;

    protected Membership() {
    }

    public Membership(UUID orgId, UUID userId, Role role) {
        this.id = new MembershipId(orgId, userId);
        this.role = role;
    }

    public MembershipId getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

}
