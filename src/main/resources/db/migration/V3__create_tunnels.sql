CREATE TABLE tunnels (
    id               VARCHAR(36)     PRIMARY KEY,
    name             VARCHAR(255)    NOT NULL,
    src_addr         VARCHAR(255)    NOT NULL,
    src_port         VARCHAR(10)     NOT NULL,
    dst_port         VARCHAR(10)     NOT NULL,
    use_this_server  BOOLEAN         NOT NULL DEFAULT FALSE,
    state            VARCHAR(20)     NOT NULL DEFAULT 'STOPPED',
    assigned_port    INTEGER,
    gateway_id       VARCHAR(100)    NOT NULL REFERENCES gateways(id) ON DELETE CASCADE
);
