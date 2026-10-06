package io.github.orhanyarkin.saiman.shared.eval;

/** One golden-set question for {@code POST /internal/v1/eval/questions}; bounds are those of the paid endpoint. */
public record EvalAnswerRequest(String ticker, String question) {}
