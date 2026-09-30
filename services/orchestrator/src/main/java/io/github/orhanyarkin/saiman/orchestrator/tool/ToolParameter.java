package io.github.orhanyarkin.saiman.orchestrator.tool;

/**
 * The only parameters any research tool may have (ADR-0013): no URL, amount, payee, budget or key
 * can ever be a tool argument, because there is no enum constant for one.
 */
public enum ToolParameter {
    /** A BIST ticker, {@code [A-Z0-9]{3,6}}, also present in the seller's free catalogue. */
    TICKER("ticker", "BIST ticker symbol, 3-6 upper-case letters or digits, for example THYAO"),

    /** A question about the company's disclosures, 3-500 characters. */
    QUESTION("question", "A question about the company's public KAP disclosures, 3 to 500 characters");

    private final String jsonName;
    private final String description;

    ToolParameter(String jsonName, String description) {
        this.jsonName = jsonName;
        this.description = description;
    }

    /** The argument's name in the tool's JSON input. */
    public String jsonName() {
        return jsonName;
    }

    public String description() {
        return description;
    }
}
