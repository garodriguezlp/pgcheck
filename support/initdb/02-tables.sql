SET search_path TO store, public;

CREATE TABLE customers (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER      NOT NULL REFERENCES customers(id),
    status      VARCHAR(20)  NOT NULL DEFAULT 'pending'
                             CHECK (status IN ('pending', 'shipped', 'delivered', 'cancelled')),
    total_cents INTEGER      NOT NULL CHECK (total_cents >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ON orders (customer_id);
CREATE INDEX ON orders (status);
