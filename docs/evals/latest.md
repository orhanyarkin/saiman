# Retrieval eval (Tier R)

- Generated: 2026-10-05T19:32:32.470288734Z
- Git sha: `dd7a79f`
- Chunks requested per query (topK): 10
- Golden-set corpus version: `24303757d747de77`
- Live corpus version: `24303757d747de77` (newest disclosure 2023-12-29T20:46:52Z)

## Summary by kind

| Kind | n | errors | metric | mean |
|---|---:|---:|---|---:|
| RETRIEVAL | 16 | 0 | recall@10 | 0.927 |
| RETRIEVAL | 16 | 0 | mrr@10 | 0.911 |
| RETRIEVAL | 16 | 0 | ndcg@10 | 0.870 |
| RETRIEVAL | 16 | 0 | p95 latency (ms) | 1158 |
| FRESHNESS | 3 | 0 | recency@5 | 0.533 |
| FRESHNESS | 3 | 0 | latestHit@5 | 0.667 |
| FRESHNESS | 3 | 0 | ndcg@10 | 0.720 |
| FRESHNESS | 3 | 0 | p95 latency (ms) | 213 |

## RETRIEVAL items

| id | ticker | question | recall@10 | mrr@10 | ndcg@10 | top 5 disclosures | ms |
|---|---|---|---:|---:|---:|---|---:|
| R-ARCLK | ARCLK | ARCLK yönetim kurulu komitelerinin oluşturulmasına ilişkin Mart 2023 bildirimi | 1.000 | 0.250 | 0.431 | 1124305, 1115722, 1130485, 1126828, 1115721 | 798 |
| R-ASELS | ASELS | ASELS esas sözleşme tadili bildirimi Ağustos 2023 | 1.000 | 1.000 | 0.930 | 1141480, 1188720, 1142914, 1196000, 1190473 | 1158 |
| R-BIMAS | BIMAS | BIMAS bağımsız denetim kuruluşunun belirlenmesi Haziran 2023 | 1.000 | 1.000 | 0.913 | 1138989, 1156123, 1156120, 1123892, 1123884 | 206 |
| R-EREGL | EREGL | EREGL pay geri alım bildirimi Mayıs 2023 | 1.000 | 1.000 | 1.000 | 1153942, 1130709, 1126471, 1140592, 1215175 | 230 |
| R-FROTO | FROTO | FROTO kar payı dağıtım işlemleri bildirimi Ekim 2023 | 1.000 | 1.000 | 0.913 | 1122330, 1210408, 1127168, 1219331 | 210 |
| R-KCHOL | KCHOL | KCHOL birleşme işlemlerine ilişkin bildirim Temmuz 2023 | 1.000 | 1.000 | 1.000 | 1172777, 1124296, 1175222, 1189291, 1161448 | 250 |
| R-KRDMD | KRDMD | KRDMD genel kurul işlemleri bildirimi Kasım 2023 | 1.000 | 1.000 | 0.913 | 1133692, 1216303, 1120206, 1156587, 1208785 | 203 |
| R-PETKM | PETKM | PETKM ilişkili taraf işlemleri açıklaması Nisan 2023 | 1.000 | 1.000 | 1.000 | 1135750, 1141368, 1105033, 1148198, 1157459 | 205 |
| R-PGSUS | PGSUS | PGSUS kar payı dağıtım bildirimi Mart 2023 | 1.000 | 1.000 | 1.000 | 1129697, 1134545, 1120710, 1120714, 1134559 | 222 |
| R-SAHOL | SAHOL | SAHOL finansal duran varlık satışı bildirimi Aralık 2023 | 1.000 | 1.000 | 0.913 | 1208705, 1224207, 1217142, 1109672, 1201024 | 230 |
| R-SASA | SASA | SASA sermaye artırımı azaltımı işlemleri bildirimi Eylül 2023 | 1.000 | 1.000 | 0.940 | 1103603, 1191825, 1218960, 1154496, 1099177 | 206 |
| R-SISE | SISE | SISE esas sözleşme tadili bildirimi Şubat 2023 | 0.500 | 1.000 | 0.674 | 1114775, 1117164, 1140862, 1229108, 1149954 | 341 |
| R-TCELL | TCELL | TCELL finansal duran varlık edinimi bildirimi Ağustos 2023 | 0.333 | 0.333 | 0.285 | 1173266, 1139226, 1180413, 1204245, 1230986 | 212 |
| R-THYAO | THYAO | THYAO payların geri alınmasına ilişkin bildirim Ekim 2023 | 1.000 | 1.000 | 1.000 | 1207592, 1179573, 1171060, 1175196, 1216141 | 182 |
| R-TOASO | TOASO | TOASO geleceğe dönük değerlendirmeler açıklaması Şubat 2023 | 1.000 | 1.000 | 1.000 | 1109294, 1102651, 1226033, 1129519, 1115059 | 234 |
| R-TUPRS | TUPRS | TUPRS geleceğe dönük değerlendirmeler açıklaması Temmuz 2023 | 1.000 | 1.000 | 1.000 | 1177642, 1111641, 1210025, 1111647, 1142655 | 206 |

## FRESHNESS items

| id | ticker | question | recency@5 | latestHit@5 | ndcg@10 | top 5 disclosures | ms |
|---|---|---|---:|---:|---:|---|---:|
| F-THYAO | THYAO | THYAO son özel durum açıklamaları | 0.400 | 0.000 | 0.539 | 1226178, 1230391, 1221201, 1217358, 1216142 | 207 |
| F-ASELS | ASELS | ASELS en son açıklamaları | 0.600 | 1.000 | 0.726 | 1196957, 1230557, 1226156, 1223954, 1185770 | 191 |
| F-SISE | SISE | SISE güncel bildirimleri | 0.600 | 1.000 | 0.893 | 1229869, 1229108, 1220175, 1197343, 1209647 | 213 |

## Tier A: answers

Deterministic scoring only, no LLM judge (ADR-0003, ADR-0025). Questions sent: 9.

> **Date caveat:** a required fact that is a publication date is answerable only because the answer service gives the model each excerpt's `published` timestamp from the document metadata (Europe/Istanbul offset); it does not test reading the date out of the disclosure text.

### Summary by kind

| Kind | n | scored | metric | value |
|---|---:|---:|---|---:|
| ANSWER | 5 | 5 | taskSuccess | 1.000 |
| ANSWER | 5 | 5 | factRecall | 1.000 |
| ANSWER | 5 | 5 | citationRecall | 1.000 |
| ANSWER | 5 | 5 | relativeTimeFree | 1.000 |
| ANSWER | 5 | 5 | citationValidity | 1.000 |
| UNANSWERABLE | 2 | 2 | refusalCorrect | 1.000 |
| UNANSWERABLE | 2 | 2 | citationValidity | 1.000 |
| TEMPORAL | 2 | 2 | temporalSuccess | 1.000 |
| TEMPORAL | 2 | 2 | relativeTimeFree | 1.000 |
| TEMPORAL | 2 | 2 | citationValidity | 1.000 |

### Outcomes

| Outcome | count |
|---|---:|
| ANSWERED | 7 |
| REFUSED | 1 |
| NO_VALID_CITATIONS | 1 |

### Cost and latency

- Total model cost: $0.010193 (10193 micro-USD)
- USD per question (mean over answered calls): $0.001133
- p95 latency: 10024 ms

### Items

| id | ticker | status | outcome | citations (valid/total) | basis | citation recall | fact recall | relative time | correct | USD | ms |
|---|---|---|---|---:|---|---:|---:|---|---|---:|---:|
| A2-ARCLK-1125721-1178832 | ARCLK | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true | $0.000966 | 4110 |
| A2-THYAO-1207592-1152405 | THYAO | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true | $0.001076 | 3459 |
| A2-KCHOL-1172777-1103107 | KCHOL | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true | $0.001328 | 6144 |
| A2-SAHOL-1219722-1129966 | SAHOL | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true | $0.000961 | 2979 |
| A2-EREGL-1153942-1210600 | EREGL | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true | $0.000981 | 4746 |
| U-AKBNK | AKBNK | OK | REFUSED | 0/0 | RETRIEVAL | - | - | - | true | $0.000000 | 18 |
| U-THYAO-2025 | THYAO | OK | NO_VALID_CITATIONS | 1/1 | RETRIEVAL | - | - | none | true | $0.001369 | 5649 |
| T-SISE-GUNCEL | SISE | OK | ANSWERED | 8/8 | RETRIEVAL | - | - | none | true | $0.001690 | 8130 |
| T-KCHOL-ENSON | KCHOL | OK | ANSWERED | 4/4 | RETRIEVAL | - | - | none | true | $0.001822 | 10024 |

## Cost

Tier R makes 19 retrieval calls; each embeds one short query through ingest's model-router route (embeddings only, no generation, no x402 payment), about $0.00002 per run (ADR-0025). The answer tier's cost, when it ran, is in its own section.
