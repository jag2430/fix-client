-- Initial database setup for FIX Client
-- Tables will be created by JPA/Hibernate, but we can add indexes here

-- Create indexes for better query performance (run after Hibernate creates tables)
-- These will be created on first app startup via Hibernate

-- Grant permissions (if needed for additional users)
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO fixuser;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO fixuser;
