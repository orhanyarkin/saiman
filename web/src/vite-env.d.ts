/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Toggles browser OpenTelemetry tracing. Off in replay builds. Optional: production builds
   * don't load `.env.development`, so an unset value must be treated the same as "false".
   */
  readonly VITE_OTEL_ENABLED?: "true" | "false";
  /** OTLP/HTTP traces endpoint, proxied to the collector in dev. Required only when tracing is on. */
  readonly VITE_OTEL_TRACES_URL?: string;
  /** Selects the data-source implementation behind every screen; see ADR-0004. */
  readonly VITE_DEMO_MODE?: "live" | "replay";
  /** Names the frozen KAP snapshot on the landing page; default "KAP disclosures up to 29 Dec 2023". */
  readonly VITE_CORPUS_SNAPSHOT_LABEL?: string;
  /** Where the replay build (and the live app's "recorded demo") fetches the capture from. */
  readonly VITE_REPLAY_CAPTURE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
