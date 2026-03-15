CREATE TABLE users (
    id            BIGSERIAL       PRIMARY KEY,
    username      VARCHAR(100)    NOT NULL UNIQUE,
    password_hash VARCHAR(255)    NOT NULL
);
