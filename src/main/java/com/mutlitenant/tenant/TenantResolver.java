package com.mutlitenant.tenant;

import jakarta.servlet.http.HttpServletRequest;

public interface TenantResolver {

	String resolveTenant(HttpServletRequest request);
}
