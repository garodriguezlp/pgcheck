SET search_path TO store, public;

INSERT INTO customers (name, email) VALUES
    ('Alice Martin',  'alice@example.com'),
    ('Bob Chen',      'bob@example.com'),
    ('Carol Davis',   'carol@example.com'),
    ('David Kim',     'david@example.com'),
    ('Eve Nakamura',  'eve@example.com');

INSERT INTO orders (customer_id, status, total_cents) VALUES
    (1, 'delivered', 4999),
    (1, 'shipped',   12500),
    (2, 'delivered', 799),
    (3, 'pending',   23100),
    (3, 'cancelled', 5000),
    (4, 'delivered', 8750),
    (5, 'pending',   31200),
    (5, 'shipped',   1499),
    (2, 'delivered', 6600),
    (4, 'pending',   9900);
