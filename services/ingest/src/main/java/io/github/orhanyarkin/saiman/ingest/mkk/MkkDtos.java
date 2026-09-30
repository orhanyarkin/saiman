package io.github.orhanyarkin.saiman.ingest.mkk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire shapes of the MKK KAP data API (only the fields the pipeline uses; ADR-0010). */
public final class MkkDtos {

    private MkkDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LastIndex(String lastDisclosureIndex) {}

    /** A listed company or other member; {@code stockCode} may hold several comma-separated codes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(
            long id,
            @Nullable String title,
            @Nullable String stockCode,
            @Nullable String memberType) {}

    /** One row of the windowed {@code /disclosures} listing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisclosureSummary(
            long disclosureIndex,
            @Nullable String disclosureType,
            @Nullable String disclosureClass,
            @Nullable String title,
            long companyId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Localized(@Nullable String tr, @Nullable String en) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HtmlMessage(
            @Nullable String id,
            @Nullable String tr,
            @Nullable String en) {}

    /** {@code /disclosureDetail/{idx}?fileType=html}. {@code time} is {@code dd.MM.yyyy HH:mm:ss} in Istanbul time. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisclosureDetail(
            long disclosureIndex,
            @Nullable String disclosureReason,
            @Nullable String relatedDisclosureIndex,
            @Nullable String disclosureType,
            @Nullable String disclosureClass,
            @Nullable Localized subject,
            @Nullable String time,
            @Nullable List<HtmlMessage> htmlMessages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockedDisclosure(@Nullable String blockedType, long disclosureIndex) {}
}
