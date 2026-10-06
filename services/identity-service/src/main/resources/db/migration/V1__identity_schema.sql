-- identity-service schema: users, organizations (tenants), memberships, refresh tokens.
-- IDs use uuidv7() (PostgreSQL 18): time-ordered, so inserts append to the index instead of scattering.

CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    -- The application lower-cases emails; the CHECK makes that rule impossible to skip.
    email         text        NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash text        NOT NULL, -- bcrypt
    display_name  text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- An organization is the tenant. Tenant-owned tables reference it through org_id.
CREATE TABLE organizations (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    name       text        NOT NULL,
    slug       text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- A user belongs to many orgs, with one role per org.
CREATE TABLE memberships (
    org_id     uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- org_id first: tenant-scoped queries ("members of org X") use the primary key.
    PRIMARY KEY (org_id, user_id)
);
-- "Which orgs am I in?" (login, org switcher)
CREATE INDEX memberships_user_id_idx ON memberships (user_id);

-- Opaque refresh tokens. Only a SHA-256 hash is stored: the token is 256 random bits, so a fast hash
-- is safe and allows lookup by hash (bcrypt is for low-entropy passwords).
-- Rotation: each use revokes the token and issues a new one in the same family. If a revoked token
-- is presented again, it was stolen or replayed, so the whole family gets revoked.
CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Active org for the access tokens minted from this refresh token. NULL until the user has an org.
    org_id      uuid        REFERENCES organizations (id) ON DELETE SET NULL,
    token_hash  bytea       NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    family_id   uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
