-- Emails are stored lower-case and unique case-insensitively.
--
-- Before this, "Owner@x.com" and "owner@x.com" were two different rows (the UNIQUE
-- constraint is case-sensitive) while the owner-role check compared case-insensitively, so
-- registering a case-variant of the owner's address granted owner rights.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(email) HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            'users has emails that differ only by case; merge or remove the duplicates manually, then re-run this migration';
    END IF;
END $$;

UPDATE users SET email = lower(email) WHERE email <> lower(email);

CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));

-- Belt and braces: any future writer that forgets to normalise is rejected by the database.
ALTER TABLE users ADD CONSTRAINT users_email_is_lowercase CHECK (email = lower(email));
