-- Seed a few well-known prompts. `reference_solution` left NULL here: attach vetted
-- references out-of-band (PRD 9) so the Council node is skipped for those sessions.
-- Verify licensing of any external source before pasting its text/diagrams in (PRD 9).

-- source/status use the Java enum constant names (Question.source/status are mapped with
-- @Enumerated(EnumType.STRING), which persists/queries Enum.name() - i.e. uppercase).
INSERT INTO questions (slug, title, prompt, source, status) VALUES
('url-shortener',
 'Design a URL shortener',
 'Design a service like TinyURL: accept a long URL, return a short code, redirect on lookup. Discuss key generation, storage, read/write scale, caching, and analytics.',
 'SEED', 'APPROVED'),
('news-feed',
 'Design a social media news feed',
 'Design the news feed for a social network: fan-out on write vs read, ranking, pagination, and handling celebrity accounts with millions of followers.',
 'SEED', 'APPROVED'),
('rate-limiter',
 'Design a distributed rate limiter',
 'Design a rate limiter usable as a shared service across many API servers. Cover algorithm choice (token bucket, sliding window), where state lives, and failure modes.',
 'SEED', 'APPROVED'),
('chat-system',
 'Design a real-time chat system',
 'Design a 1:1 and group chat system: connection management, message delivery and ordering, presence, offline delivery, and read receipts.',
 'SEED', 'APPROVED');
