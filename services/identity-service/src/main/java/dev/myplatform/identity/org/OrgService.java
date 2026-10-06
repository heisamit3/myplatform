package dev.myplatform.identity.org;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgService {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;

    OrgService(OrganizationRepository organizations, MembershipRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    /** The creator becomes OWNER. Org and membership commit together or not at all. */
    @Transactional
    OrgResponse create(UUID userId, CreateOrgRequest request) {
        if (organizations.existsBySlug(request.slug())) {
            throw new SlugTakenException();
        }
        Organization org = new Organization(request.name(), request.slug());
        try {
            organizations.saveAndFlush(org);
        } catch (DataIntegrityViolationException e) {
            // Concurrent create with the same slug; the UNIQUE constraint decides.
            throw new SlugTakenException();
        }
        memberships.save(new Membership(org.getId(), userId, Role.OWNER));
        return new OrgResponse(org.getId(), org.getName(), org.getSlug(), Role.OWNER);
    }

}
