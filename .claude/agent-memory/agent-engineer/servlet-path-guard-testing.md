---
name: servlet-path-guard-testing
description: Path-based servlet filters must key on the normalised servlet path, and path-bypass tests need a raw socket against real Tomcat (MockMvc/HTTP clients normalise)
metadata:
  type: project
---
A filter that decides on `getRequestURI()` prefixes is bypassable: Spring MVC PathPattern matching decodes segments and drops `;` params, so `/api;x=1/...`, `/%61pi/...`, `//api/...`, `/x/../api/...` reach `/api` handlers. The orchestrator guard (M3 T3 audit fix, 2026-09-30) refuses raw URIs with `;`, `%`, `\`, `//` or raw != servletPath+pathInfo, and applies Host/CSRF checks on every path.

Observed Tomcat (Boot 4.1) behaviour: `%2F` in the path is refused by Tomcat itself with 400 before filters; HTTP/1.1 without Host is refused by Tomcat with 400; HTTP/1.0 without Host reaches the filter. On real Tomcat with DispatcherServlet at `/`, servletPath is the whole decoded path and pathInfo is null; MockMvc sets servletPath "" and pathInfo = path; a bare MockHttpServletRequest needs `setServletPath` in unit tests.

**Why:** the security auditor found this bypass on real Tomcat; MockMvc tests passed because MockMvc normalises the URI.
**How to apply:** for any path-scoped servlet filter (ingest has the same pattern), test with a raw `java.net.Socket` request line against `RANDOM_PORT` and verify the test fails on the old code.
