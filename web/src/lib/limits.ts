/**
 * Spend limits shown in plain-language text and used as form defaults. These mirror the
 * orchestrator's configuration defaults (`SpendProperties`); T3b replaces them with the values
 * from `GET /api/v1/spend`. They are display hints only: the server enforces every limit.
 */
export const limits = {
  defaultRunBudgetAtomic: 50_000,
  maxRunBudgetAtomic: 200_000,
  approvalThresholdAtomic: 10_000,
} as const;

export const QUESTION_MIN = 3;
export const QUESTION_MAX = 500;
export const EXAMPLE_QUESTION = "THYAO son özel durum açıklamaları neler?";
