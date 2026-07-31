create table if not exists billing_plans (
	id bigserial primary key,
	code varchar(64) not null,
	name varchar(255) not null,
	description varchar(1000),
	amount numeric(19, 2) not null,
	currency varchar(3) not null,
	billing_interval varchar(16) not null,
	active boolean not null,
	created_at timestamp with time zone not null,
	updated_at timestamp with time zone not null,
	constraint uk_billing_plans_code unique (code),
	constraint chk_billing_plans_amount_non_negative check (amount >= 0),
	constraint chk_billing_plans_currency check (currency ~ '^[A-Z]{3}$'),
	constraint chk_billing_plans_interval check (billing_interval in ('MONTHLY', 'YEARLY'))
);

create index if not exists idx_billing_plans_active on billing_plans(active);
