-- Placeholder identity used by the frontend until real auth lands (PRD 6.2).
-- Matches DEMO_USER_ID in frontend/src/pages/HomePage.tsx.
INSERT INTO users (id, email, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'demo@ossature.local', 'Demo Candidate');
