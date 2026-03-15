CREATE TABLE gateways (
    id         VARCHAR(100)    PRIMARY KEY,
    public_key TEXT            NOT NULL,
    status     VARCHAR(20)     NOT NULL DEFAULT 'UNKNOWN',
    owner_id   BIGINT          NOT NULL REFERENCES users(id),

    CONSTRAINT chk_gateway_status CHECK (status IN ('ONLINE', 'OFFLINE', 'UNKNOWN'))
);
