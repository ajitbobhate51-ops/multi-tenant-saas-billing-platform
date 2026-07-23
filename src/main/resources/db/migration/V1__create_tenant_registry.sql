create table if not exists tenants (
	tenant_id varchar(63) primary key,
	tenant_name varchar(255) not null unique,
	schema_name varchar(63) not null unique,
	status varchar(32) not null,
	provisioning_status varchar(32) not null,
	failure_message varchar(1000),
	created_at timestamp with time zone not null,
	updated_at timestamp with time zone not null
);

create index if not exists idx_tenants_status on tenants(status);
create index if not exists idx_tenants_provisioning_status on tenants(provisioning_status);
