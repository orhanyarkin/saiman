# ADR-0004: One real AWS deployment, captured, then a static replay demo

Status: Accepted (revised twice: Oracle Always Free rejected; always-on VPS dropped)

## Context
The project must prove it really runs in the cloud and be easy for anyone to look at, at close to zero running cost. Recruiters read the README and watch a short video; interviewers read the code and ask how it was deployed. Nobody needs a 24/7 backend. Keeping the full stack (Postgres, Redpanda, Valkey, five Spring services) always on costs $9+/month on a VPS or $50–100+/month on AWS. An AWS Free Plan account ($100 sign-up credit + up to $100 more from onboarding activities, closes after 6 months — this account: 27 March 2027) covers short-lived deployments.

## Decision
- **Real deployment on AWS, on demand**: Terraform `demo-lite` (ECS Fargate ARM64, public subnets, no NAT Gateway, ALB, RDS Postgres `db.t4g.micro` with pgvector, Redpanda and Valkey as tasks) in `eu-central-1`. Brought up and torn down by manual GitHub Actions workflows (`demo-up` / `demo-down`) using OIDC; `demo-up` schedules an automatic destroy after `ttl_hours` (default 4). Budget alerts at $5 / $20; Infracost on every Terraform PR.
- **Capture**: while it runs, execute scripted research runs and export everything the UI needs — each run's ordered event stream (the same events the SSE stream sends), ledger and reconciliation snapshots, seller revenue, eval report — as versioned JSON (`make capture-demo`). Also record the demo video, Grafana screenshots and the `terraform plan` / Infracost output.
- **Public demo = static replay**: the React app built with `VITE_DEMO_MODE=replay` reads the captured JSON instead of the APIs and replays runs with their original timing. Hosted on Cloudflare Pages (free, `*.pages.dev` or an own domain later). Every screen shows a banner: "Recorded run on AWS eu-central-1, <date> — testnet". Nothing pretends to be live.
- **Live on request**: before an important interview the human runs `demo-up` (~15 min) and shows the real system, then `demo-down`.
- Helm charts / Kubernetes are a stretch goal (EKS `enterprise` profile), not part of the core path.

## Consequences
+ Running cost ≈ $0/month; each AWS session ≈ $1–3, paid from credits.
+ Real IaC + real cloud run, with evidence in the repo; the demo never breaks, never hits LLM caps and never exposes wallets or APIs to the internet.
+ The replay mode doubles as the LLM-cap fallback already required by ADR-0003.
− No "go try it now" link; mitigated by the honest banner, the video and live-on-request.
− Credits and the Free Plan expire (27 March 2027); after that each session is paid (still a few dollars). Capture must be redone when features change.

## Rejected
- Oracle Always Free: heavy sign-up verification, unreliable ARM capacity.
- Always-on VPS (Contabo/Hetzner, ~$9/month + domain): works, but pays monthly for traffic that doesn't exist.
- AWS always-on: $50–100+/month.
