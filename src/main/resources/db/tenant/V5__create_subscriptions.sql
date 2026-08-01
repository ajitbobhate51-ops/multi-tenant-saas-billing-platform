create table if not exists subscriptions (
	id bigserial primary key,
	customer_id bigint not null,
	plan_id bigint not null,
	status varchar(16) not null,
	started_at timestamp with time zone not null,
	cancelled_at timestamp with time zone,
	created_at timestamp with time zone not null,
	updated_at timestamp with time zone not null,
	constraint fk_subscriptions_customer foreign key (customer_id) references customers(id),
	constraint fk_subscriptions_plan foreign key (plan_id) references billing_plans(id),
	constraint chk_subscriptions_status check (status in ('ACTIVE', 'CANCELLED'))
);

create index if not exists idx_subscriptions_customer_plan_status on subscriptions(customer_id, plan_id, status);

create index if not exists idx_subscriptions_customer_id on subscriptions(customer_id);
create index if not exists idx_subscriptions_plan_id on subscriptions(plan_id);
create index if not exists idx_subscriptions_status on subscriptions(status);
