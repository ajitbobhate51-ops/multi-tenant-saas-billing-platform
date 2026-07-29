create table if not exists tenant_users (
	id bigserial primary key,
	email varchar(320) not null unique,
	password_hash varchar(255) not null,
	enabled boolean not null,
	role varchar(64) not null,
	created_at timestamp with time zone not null,
	updated_at timestamp with time zone not null
);

create unique index if not exists idx_tenant_users_email on tenant_users(email);
