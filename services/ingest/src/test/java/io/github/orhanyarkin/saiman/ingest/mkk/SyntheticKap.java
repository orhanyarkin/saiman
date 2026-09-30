package io.github.orhanyarkin.saiman.ingest.mkk;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthetic disclosures with the shape of the real MKK responses and none of its text (no KAP
 * content is committed). Layout, with the windowed listing at 3000 indexes per page:
 *
 * <pre>
 * THYAO (1107)  1093000 ODA NEW   "Yollari"/"yolları" + Istanbul table, later corrected
 *               1093500 DG  NEW   long governance form (several chunks), mentions "ısparta"
 *               1094000 FR        skipped (class not configured)
 *               1096000 DG  NEW   later cancelled
 *               1100000 ODA CORR  corrects 1093000; mentions "İstanbul"
 *               1100500 ODA NEW   blocked by KAP (never fetched)
 *               1101500 ODA NEW   plain, mentions "yolları"
 *               1102000 DG  CANC  cancels 1096000
 * ASELS (1108)  1095000 ODA NEW, 1103000 ODA NEW
 * </pre>
 */
public final class SyntheticKap {

    public static final String CREDENTIALS = "c3ludGhldGljOm1hcmtlci1OT1QtQS1TRUNSRVQ=";
    public static final long THYAO = 1107;
    public static final long ASELS = 1108;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SyntheticKap() {}

    public static void load(FakeMkkServer server) {
        server.lastIndex(1_105_000);
        server.members(json(List.of(
                Map.of("id", 1107, "title", "TÜRK HAVA YOLLARI A.O.", "stockCode", "THYAO", "memberType", "IGS"),
                Map.of("id", 1108, "title", "ASELSAN A.Ş.", "stockCode", "ASELS,ASELSB", "memberType", "IGS"),
                Map.of("id", 1109, "title", "BIR BANKA", "stockCode", "BANKX", "memberType", "BNK"))));
        server.blocked(json(List.of(
                Map.of("blockedType", "Disclosure", "disclosureIndex", 1_100_500),
                Map.of("blockedType", "Attachment", "disclosureIndex", 1_093_000))));

        add(
                server,
                THYAO,
                1_093_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Filo yatırımı",
                "NEW",
                null,
                "12.03.2023 09:15:00",
                page(
                        "Filo yatırımı",
                        "Şirketimiz yeni uçak yolları ve İstanbul merkezli hatlar için sipariş verdi.",
                        "<table><tr><th>Kalem</th><th>Adet</th></tr><tr><td>Gövde</td><td>12</td></tr></table>"));
        add(
                server,
                THYAO,
                1_093_500,
                "DG",
                "Diğer",
                "Kurumsal yönetim uyum raporu",
                "NEW",
                null,
                "13.03.2023 10:00:00",
                longGovernanceForm());
        server.list(THYAO, 1_094_000, "FR", "FR", "Finansal Rapor");
        add(
                server,
                THYAO,
                1_096_000,
                "DG",
                "Diğer",
                "Bağımsız denetim görüşü",
                "NEW",
                null,
                "20.04.2023 11:00:00",
                page("Denetim", "Denetim kuruluşu görüşünü bildirmiştir."));
        add(
                server,
                THYAO,
                1_100_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Düzeltme: Filo yatırımı",
                "CORR",
                "1093000",
                "02.06.2023 14:30:00",
                page(
                        "Filo yatırımı (düzeltme)",
                        "Önceki açıklamadaki adet düzeltilmiştir; İstanbul hattı sayısı 14 olmuştur."));
        server.list(THYAO, 1_100_500, "ODA", "ODA", "Engellenen bildirim");
        add(
                server,
                THYAO,
                1_101_500,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Ortaklık yolları görüşmesi",
                "NEW",
                null,
                "15.07.2023 16:45:00",
                page("Görüşme", "Yönetim kurulu, yolları ve iştirak ortaklığı görüşmelerini tamamladı."));
        add(
                server,
                THYAO,
                1_102_000,
                "DG",
                "Diğer",
                "İptal bildirimi",
                "CANC",
                "1096000",
                "16.07.2023 08:00:00",
                page("İptal", "Önceki bildirim iptal edilmiştir."));

        add(
                server,
                ASELS,
                1_095_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Savunma sözleşmesi",
                "NEW",
                null,
                "05.04.2023 09:00:00",
                page("Sözleşme", "Şirket yeni bir savunma sistemleri sözleşmesi imzaladı."));
        add(
                server,
                ASELS,
                1_103_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Radar teslimatı",
                "NEW",
                null,
                "10.08.2023 09:00:00",
                page("Teslimat", "Radar sistemlerinin teslimatı takvime uygun tamamlandı."));
    }

    private static void add(
            FakeMkkServer server,
            long companyId,
            long index,
            String cls,
            String type,
            String subject,
            String reason,
            String related,
            String time,
            String html) {
        server.list(companyId, index, cls, type, subject);
        server.detail(index, detailJson(index, cls, type, subject, reason, related, time, html));
    }

    public static String detailJson(
            long index,
            String cls,
            String type,
            String subject,
            String reason,
            String related,
            String time,
            String html) {
        String encoded = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        return json(Map.ofEntries(
                Map.entry("disclosureIndex", index),
                Map.entry("senderId", 1),
                Map.entry("disclosureReason", reason),
                Map.entry("relatedDisclosureIndex", related == null ? "" : related),
                Map.entry("disclosureType", type),
                Map.entry("disclosureClass", cls),
                Map.entry("subject", Map.of("tr", subject, "en", "")),
                Map.entry("summary", Map.of("tr", "", "en", "")),
                Map.entry("time", time),
                Map.entry("link", "https://test.invalid/ignored"),
                Map.entry("htmlMessages", List.of(Map.of("id", "1", "tr", encoded, "en", ""))),
                Map.entry("year", 2023)));
    }

    /** XHTML whose header claims ISO-8859-9 while the bytes are UTF-8, with a style block and a comment. */
    public static String page(String heading, String... paragraphs) {
        StringBuilder html = new StringBuilder("<?xml version=\"1.0\" encoding=\"ISO-8859-9\"?>\n")
                .append("<html><head><style type=\"text/css\">.k{font-family:Arial;color:#333}</style></head>")
                .append("<body><!-- internal note --><h1>")
                .append(heading)
                .append("</h1>");
        for (String p : paragraphs) {
            html.append(p.startsWith("<") ? p : "<p>" + p + "</p>");
        }
        return html.append("</body></html>").toString();
    }

    /** About 60 lines of governance boilerplate: several chunks at 400 tokens. */
    public static String longGovernanceForm() {
        StringBuilder rows =
                new StringBuilder("<p>Kurumsal yönetim ilkelerine uyum beyanı. Merkez ısparta şubesi dahil.</p>");
        for (int i = 1; i <= 60; i++) {
            rows.append("<p>İlke ")
                    .append(i)
                    .append(
                            ": Yönetim kurulu bağımsız üyelerin görevlendirilmesi, denetim komitesinin çalışma esasları,")
                    .append(" pay sahipleriyle ilişkiler ve kamuyu aydınlatma politikası kapsamında uyum sağlanmıştır")
                    .append(" ve gerekçeli açıklama ")
                    .append(i)
                    .append(" numaralı maddede yer almaktadır.</p>");
        }
        return page("Uyum raporu", rows.toString());
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
