-- FCM Tokens Table for Push Notifications
-- Stores Firebase Cloud Messaging tokens for each device

CREATE TABLE IF NOT EXISTS fcm_tokens (
    id SERIAL PRIMARY KEY,
    token TEXT NOT NULL UNIQUE,
    platform VARCHAR(20) DEFAULT 'android',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for quick token lookup
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_active ON fcm_tokens(is_active) WHERE is_active = TRUE;

-- Enable RLS
ALTER TABLE fcm_tokens ENABLE ROW LEVEL SECURITY;

-- Allow insert/update from authenticated and anon users (app sends token)
CREATE POLICY "Allow token insert" ON fcm_tokens FOR INSERT WITH CHECK (true);
CREATE POLICY "Allow token update" ON fcm_tokens FOR UPDATE USING (true);
CREATE POLICY "Allow token select" ON fcm_tokens FOR SELECT USING (true);
