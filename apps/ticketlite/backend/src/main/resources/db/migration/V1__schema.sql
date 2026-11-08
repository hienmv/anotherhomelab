create table users (
    id            bigserial primary key,
    username      text not null unique,
    password_hash text not null,
    display_name  text not null
);

create table concerts (
    id            bigserial primary key,
    title         text not null,
    artist        text not null,
    venue         text not null,
    starts_at     timestamptz not null,
    price_cents   int not null check (price_cents >= 0),
    total_tickets int not null check (total_tickets >= 0),
    available     int not null check (available >= 0),
    hot           boolean not null default false
);

create table orders (
    id          uuid primary key,
    user_id     bigint not null references users (id),
    concert_id  bigint not null references concerts (id),
    quantity    int not null check (quantity between 1 and 10),
    status      text not null check (status in ('PENDING', 'CONFIRMED', 'FAILED')),
    reason      text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index orders_user_created on orders (user_id, created_at desc);
create index orders_user_concert on orders (user_id, concert_id);
