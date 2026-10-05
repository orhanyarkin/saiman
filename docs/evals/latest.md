# Retrieval eval (Tier R)

- Generated: 2026-10-05T16:20:37.037809416Z
- Git sha: `d8cb107`
- Chunks requested per query (topK): 10
- Golden-set corpus version: `24303757d747de77`
- Live corpus version: `24303757d747de77` (newest disclosure 2023-12-29T20:46:52Z)

## Summary by kind

| Kind | n | errors | metric | mean |
|---|---:|---:|---|---:|
| RETRIEVAL | 16 | 0 | recall@10 | 0.927 |
| RETRIEVAL | 16 | 0 | mrr@10 | 0.911 |
| RETRIEVAL | 16 | 0 | ndcg@10 | 0.870 |
| RETRIEVAL | 16 | 0 | p95 latency (ms) | 2822 |
| FRESHNESS | 3 | 0 | recency@5 | 0.533 |
| FRESHNESS | 3 | 0 | latestHit@5 | 0.667 |
| FRESHNESS | 3 | 0 | ndcg@10 | 0.720 |
| FRESHNESS | 3 | 0 | p95 latency (ms) | 367 |

## RETRIEVAL items

| id | ticker | question | recall@10 | mrr@10 | ndcg@10 | top 5 disclosures | ms |
|---|---|---|---:|---:|---:|---|---:|
| R-ARCLK | ARCLK | ARCLK yönetim kurulu komitelerinin oluşturulmasına ilişkin Mart 2023 bildirimi | 1.000 | 0.250 | 0.431 | 1124305, 1115722, 1130485, 1126828, 1115721 | 2822 |
| R-ASELS | ASELS | ASELS esas sözleşme tadili bildirimi Ağustos 2023 | 1.000 | 1.000 | 0.930 | 1141480, 1188720, 1142914, 1196000, 1190473 | 291 |
| R-BIMAS | BIMAS | BIMAS bağımsız denetim kuruluşunun belirlenmesi Haziran 2023 | 1.000 | 1.000 | 0.913 | 1138989, 1156123, 1156120, 1123892, 1123884 | 846 |
| R-EREGL | EREGL | EREGL pay geri alım bildirimi Mayıs 2023 | 1.000 | 1.000 | 1.000 | 1153942, 1130709, 1126471, 1140592, 1215175 | 255 |
| R-FROTO | FROTO | FROTO kar payı dağıtım işlemleri bildirimi Ekim 2023 | 1.000 | 1.000 | 0.913 | 1122330, 1210408, 1127168, 1219331 | 240 |
| R-KCHOL | KCHOL | KCHOL birleşme işlemlerine ilişkin bildirim Temmuz 2023 | 1.000 | 1.000 | 1.000 | 1172777, 1124296, 1175222, 1189291, 1161448 | 235 |
| R-KRDMD | KRDMD | KRDMD genel kurul işlemleri bildirimi Kasım 2023 | 1.000 | 1.000 | 0.913 | 1133692, 1216303, 1120206, 1156587, 1208785 | 404 |
| R-PETKM | PETKM | PETKM ilişkili taraf işlemleri açıklaması Nisan 2023 | 1.000 | 1.000 | 1.000 | 1135750, 1141368, 1105033, 1148198, 1157459 | 228 |
| R-PGSUS | PGSUS | PGSUS kar payı dağıtım bildirimi Mart 2023 | 1.000 | 1.000 | 1.000 | 1129697, 1134545, 1120710, 1120714, 1134559 | 234 |
| R-SAHOL | SAHOL | SAHOL finansal duran varlık satışı bildirimi Aralık 2023 | 1.000 | 1.000 | 0.913 | 1208705, 1224207, 1217142, 1109672, 1201024 | 245 |
| R-SASA | SASA | SASA sermaye artırımı azaltımı işlemleri bildirimi Eylül 2023 | 1.000 | 1.000 | 0.940 | 1103603, 1191825, 1218960, 1154496, 1099177 | 214 |
| R-SISE | SISE | SISE esas sözleşme tadili bildirimi Şubat 2023 | 0.500 | 1.000 | 0.674 | 1114775, 1117164, 1140862, 1229108, 1149954 | 271 |
| R-TCELL | TCELL | TCELL finansal duran varlık edinimi bildirimi Ağustos 2023 | 0.333 | 0.333 | 0.285 | 1173266, 1139226, 1180413, 1204245, 1230986 | 227 |
| R-THYAO | THYAO | THYAO payların geri alınmasına ilişkin bildirim Ekim 2023 | 1.000 | 1.000 | 1.000 | 1207592, 1179573, 1171060, 1175196, 1216141 | 239 |
| R-TOASO | TOASO | TOASO geleceğe dönük değerlendirmeler açıklaması Şubat 2023 | 1.000 | 1.000 | 1.000 | 1109294, 1102651, 1226033, 1129519, 1115059 | 208 |
| R-TUPRS | TUPRS | TUPRS geleceğe dönük değerlendirmeler açıklaması Temmuz 2023 | 1.000 | 1.000 | 1.000 | 1177642, 1111641, 1210025, 1111647, 1142655 | 241 |

## FRESHNESS items

| id | ticker | question | recency@5 | latestHit@5 | ndcg@10 | top 5 disclosures | ms |
|---|---|---|---:|---:|---:|---|---:|
| F-THYAO | THYAO | THYAO son özel durum açıklamaları | 0.400 | 0.000 | 0.539 | 1226178, 1230391, 1221201, 1217358, 1216142 | 229 |
| F-ASELS | ASELS | ASELS en son açıklamaları | 0.600 | 1.000 | 0.726 | 1196957, 1230557, 1226156, 1223954, 1185770 | 228 |
| F-SISE | SISE | SISE güncel bildirimleri | 0.600 | 1.000 | 0.893 | 1229869, 1229108, 1220175, 1197343, 1209647 | 367 |

## Not scored in this tier

- ANSWER: 5 item(s) loaded and validated (answer tier)
- UNANSWERABLE: 2 item(s) loaded and validated (answer tier)

## Tier A: answers

Tier A not run (`saiman.evals.answers.enabled=false`; `make eval EVAL_ANSWERS=1` turns it on).

## Cost

Tier R makes 19 retrieval calls; each embeds one short query through ingest's model-router route (embeddings only, no generation, no x402 payment), about $0.00002 per run (ADR-0025). The answer tier's cost, when it ran, is in its own section.
