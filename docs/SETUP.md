# Hazırlık — sırayla yap

Amaç: auto mode'un bir araç, anahtar veya onay eksikliği yüzünden takılmaması. Hepsini **sen** önceden hazırla; Claude'un araç kurmasına izin verilmiyor (CLAUDE.md kural 9).

Ortam: **Windows + WSL2 (Ubuntu) + Claude Code CLI** ana oturum; Desktop sadece diff incelemek ve paralel oturumlar için.

---

## Adım 1 — Hesaplar ve para (1–2 saat, bilgisayar başında olmana gerek yok)

- [ ] **claude.ai** → Settings → Usage: $100 cloud kredisinin son kullanma tarihine bak. Usage credits açıksa **aylık harcama limiti** koy.
- [ ] **OpenAI Platform**: API anahtarı, $5–10 yükle, aylık limit. (GPT-6 Luna, GPT-5.6 Luna, embedding)
- [ ] **Anthropic Console**: API anahtarı, $5–10 yükle, workspace spend limit. Abonelikten **ayrı** faturalanır. (Sonnet 5 batch)
- [ ] **Google AI Studio**: API anahtarı. Geliştirmede ücretsiz katman yeter; ücretsiz katmanda veriler eğitimde kullanılabilir, sadece public veri gönder. (Gemini 3.8 Flash)
- [ ] **DeepSeek** (opsiyonel): sadece public veri için fallback.
- [ ] **GitHub**: `saiman` repo'su — public, Apache-2.0 lisanslı, README ve .gitignore olmadan (kit bunları getiriyor).
- [ ] **Cloudflare** (Pages için) ve **Grafana Cloud** (EU): ücretsiz hesaplar. Domain şart değil; `*.pages.dev` yeterli.
- [ ] **AWS** (Free Plan açıldı — kredi ve hesap **27 Mart 2027**'de bitiyor, M6 bundan önce bitmeli):
  - Root kullanıcıya **MFA** ekle; root'u bir daha günlük işte kullanma.
  - **IAM Identity Center**'da kendine bir kullanıcı aç (AdministratorAccess), MFA ile. Konsola onunla gir.
  - **Access key oluşturma.** WSL'de AWS kimlik bilgisi olmayacak; gerçek `plan/apply` M6'da GitHub Actions'ta OIDC ile çalışacak (Claude kuracak, sen tetikleyeceksin).
  - "Earn AWS credits" görevleri (her biri $20): **AWS Budgets ile bütçe** ($5 ve $20 uyarı) — bunu hemen yap. EC2, Lambda, Bedrock ve RDS görevlerini de yapabilirsin; her birinde oluşturduğun kaynağı **iş bitince sil**.
  - Konsoldaki bölge seçimi önemli değil; Terraform `eu-central-1` (Frankfurt) kullanacak.

## Adım 2 — WSL2 kurulumu (30 dk)

1. PowerShell'i **yönetici** olarak aç:
   ```powershell
   wsl --install -d Ubuntu-24.04
   ```
   Bilgisayarı yeniden başlat, Ubuntu açılınca kullanıcı adı/şifre belirle.
2. `C:\Users\orhan\.wslconfig` dosyasını oluştur (RAM'ine göre ayarla, toplamın yarısı–üçte ikisi):
   ```ini
   [wsl2]
   memory=12GB
   processors=6
   ```
   Sonra PowerShell'de `wsl --shutdown`, Ubuntu'yu yeniden aç.
3. **Docker Desktop** → Settings → Resources → WSL Integration → Ubuntu-24.04'ü aç. Ubuntu içinde `docker run --rm hello-world` çalışmalı.
4. VirtualBox VM'lerini bu projede kullanma; WSL2 ile aynı sanallaştırma katmanını paylaşınca ikisi de yavaşlar.

## Adım 3 — Ubuntu içinde araçlar (45 dk)

Hepsi **Ubuntu terminalinde**:

```bash
sudo apt update && sudo apt install -y build-essential git curl unzip zip jq make

# Java 25 + Gradle (SDKMAN)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
sdk install gradle

# Node 22 + pnpm (nvm)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash
source ~/.bashrc
nvm install 22 && npm i -g pnpm

# GitHub CLI, Terraform: resmi apt kurulum talimatlarını izle
# (cli.github.com, developer.hashicorp.com/terraform/install)
gh auth login
```

Kontrol (hepsi çalışmalı):
```bash
docker run --rm hello-world && java -version && gradle -v && pnpm -v && terraform -v && gh auth status
```

## Adım 4 — Claude Code CLI (20 dk)

1. Ubuntu içinde Claude Code'u kur (code.claude.com/docs/en/setup → Linux komutu), sonra `claude` → giriş yap.
2. **LSP** (token tasarrufunun en etkilisi):
   - Java: jdtls'i `jdtls-lsp` plugin README'sindeki komutla kur → Claude içinde `/plugin install jdtls-lsp@claude-plugins-official`
   - TypeScript: `npm i -g typescript-language-server typescript` → `/plugin install typescript-lsp@claude-plugins-official`
3. `security-guidance` plugin'ini `/plugin` menüsünden kur.
4. `/statusline` ile bağlam doluluğu ve maliyeti durum satırına ekle.
5. **VS Code** (Windows'ta) + "WSL" eklentisi. Ubuntu'da repo klasöründe `code .` → kod solda, Claude VS Code terminalinde.

## Adım 5 — Testnet cüzdanları (20 dk)

- İki **throwaway** cüzdan: `buyer` ve `seller` (Foundry `cast wallet new` veya herhangi bir cüzdan aracı). Gerçek cüzdanını asla kullanma.
- Buyer'a Base Sepolia test USDC: faucet.circle.com. Facilitator gas'ı ödediği için ETH gerekmez.
- Facilitator: x402.org'un public testnet facilitator'ı kayıt istemiyor.

## Adım 6 — Repo'yu hazırla (15 dk)

```bash
mkdir -p ~/code && cd ~/code
gh repo clone orhanyarkin/saiman && cd saiman
# kit zip'ini Windows'tan kopyala: Windows Gezgini'nde \\wsl.localhost\Ubuntu-24.04\home\<kullanıcı>\code\saiman
cp .env.example .env && nano .env      # anahtarları ve cüzdan adreslerini doldur
git add -A && git commit -m "chore: bootstrap Saiman orchestration kit" && git push
```

Repo **mutlaka** Linux tarafında (`~/code`) olsun, `/mnt/c/...` altında değil — orası yavaş ve file watching bozuluyor.

## Adım 7 — Desktop'ı bağla (opsiyonel, 5 dk)

Desktop → Code → ortam seçicide **WSL → Ubuntu-24.04** → `~/code/saiman`. WSL oturumlarında plugin/LSP ve entegre terminal yok; bu yüzden sadece diff incelemek ve ikinci paralel oturum için kullan.

---

## Modeller

**Claude Code (projeyi yazan ajanlar)** — `settings.json` ve agent dosyalarında hazır:

| Rol | Model |
|---|---|
| Ana oturum | Opus 5.5 |
| architect, reviewer, security-auditor | Opus, high effort |
| payments / agent / ai engineer, frontend, infra | Sonnet 5, high effort |
| test-runner | Sonnet 5, low effort |

Haiku yok (agentic finans görevlerinde zayıf). **Fable seçme**: çoğu planda usage credits'e düşüp her istekte onay soruyor, auto mode'u durdurur. Limit daralırsa `/model opusplan`.

**Uygulama (ürünün kullandığı modeller)**: `docs/ARCHITECTURE.md` → Model routing tablosu.

## Auto mode'u takılmadan çalıştırmak

Bilinçli olarak sana sorulacaklar: `git push`, `helm install/upgrade`, `kubectl apply/delete`. Yasak: `terraform apply/destroy`, `.env` ve anahtar dosyalarını okumak.

| Takılma sebebi | Önlem |
|---|---|
| Eksik araç → Claude kurmaya çalışır, classifier `curl \| bash`'i bloklar | Adım 3 kontrol komutu geçmeden başlama |
| Eksik anahtar/cüzdan → testler yarıda kalır | Adım 1, 5, 6 (`.env` dolu) |
| Docker kapalı → Testcontainers düşer | Oturumdan önce Docker Desktop açık |
| Yeni remote, force push, prod deploy | Classifier bloklar — olması gereken |
| Art arda 3 blok → auto mode duraklar | `/permissions` → Recently denied'da nedeni gör |

## Oturum alışkanlıkları

- Milestone'lar arasında `/clear`; bağlam `docs/PROGRESS.md` ve agent memory'lerinde kalıyor.
- Haftada bir `/usage` → hangi subagent ne kadar harcıyor.
- PR'ları kendin oku; her raporun "Spring notes" bölümünden mülakat notu çıkar.
