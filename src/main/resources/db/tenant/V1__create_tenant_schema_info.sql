create table tenant_schema_info (
	id bigint primary key,
	schema_name varchar(63) not null,
	provisioned_at timestamp with time zone not null
);

insert into tenant_schema_info (id, schema_name, provisioned_at)
values (1, '${schemaName}', current_timestamp);
