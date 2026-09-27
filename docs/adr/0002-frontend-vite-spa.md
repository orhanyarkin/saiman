# ADR-0002: React + Vite SPA (TanStack Router/Query) instead of Next.js

Status: Accepted

## Context
The UI is a dashboard behind demo auth, talking to separate Spring Boot backends. Hosting must be free. Only the landing page needs to be crawlable.

## Decision
React 19 + Vite + TypeScript, TanStack Router and Query, Tailwind + shadcn/ui. Static build on Cloudflare Pages. The landing route is pre-rendered at build time.

## Alternatives
- Next.js (App Router, RSC): strongest ecosystem and hiring keyword, but its server features duplicate what our backends already do and push toward a Node runtime we'd have to host.
- TanStack Start: good fit, younger; can migrate later since routes/queries are the same libraries.

## Consequences
+ $0 hosting, no server runtime, fast dev loop.
+ Clear separation: backends own data, SPA owns presentation.
− No RSC/SSR for app pages (not needed behind auth).
