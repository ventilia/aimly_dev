CREATE TABLE login_attempts (
                                id           BIGSERIAL PRIMARY KEY,
                                identifier   VARCHAR(255) NOT NULL,   -- email или ip
                                ip_address   VARCHAR(45) NOT NULL,
                                attempted_at TIMESTAMP NOT NULL DEFAULT NOW(),
                                success      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_login_attempts_identifier  ON login_attempts(identifier);
CREATE INDEX idx_login_attempts_ip          ON login_attempts(ip_address);
CREATE INDEX idx_login_attempts_time        ON login_attempts(attempted_at);