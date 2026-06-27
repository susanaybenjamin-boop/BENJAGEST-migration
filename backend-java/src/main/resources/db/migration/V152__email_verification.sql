-- REG-VERIFY — Verificación de email por PIN al registrarse con email+contraseña.
-- Las cuentas existentes y las de Google quedan VERIFICADAS por defecto (=1);
-- el registro por email+contraseña pondrá 0 y exigirá el PIN antes de entrar.
ALTER TABLE user_accounts
    ADD COLUMN email_verified          TINYINT(1)  NOT NULL DEFAULT 1,
    ADD COLUMN verification_pin_hash   VARCHAR(255) NULL,
    ADD COLUMN verification_expires_at TIMESTAMP    NULL,
    ADD COLUMN verification_attempts   INT          NOT NULL DEFAULT 0;
