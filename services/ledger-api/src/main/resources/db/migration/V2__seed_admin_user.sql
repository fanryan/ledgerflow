INSERT INTO users (
    id,
    email,
    password_hash,
    role,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin@ledgerflow.local',
    '$2a$10$8PJgd14BwCJpOpDLzJZ9xelf0h4wBF7NA.0xxSezSvsIC1GCsNgy.',
    'ADMIN',
    now(),
    now()
);