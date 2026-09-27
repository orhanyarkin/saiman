# Kickoff — ilk oturum ve milestone döngüsü

Önce `docs/SETUP.md`'nin 1–6. adımlarını bitir. Adım 3'teki kontrol komutu hatasız geçmeden başlama.

## İlk oturum (Ubuntu terminalinde)

```bash
cd ~/code/saiman
claude --permission-mode auto
```

Oturum açılınca sırayla:

1. `/model` → **Opus** seçili mi kontrol et (kitteki `.claude/settings.json` bunu zaten ayarlıyor).
2. `/auto-mode-setup` → GitHub repo'nu, localhost servislerini, `x402.org`, `sepolia.base.org` ve LLM API domainlerini güvenilir altyapı olarak onayla.
3. Kısa bir tanışma testi yapıştır:

```text
Read CLAUDE.md, docs/ARCHITECTURE.md, docs/PLAN.md and all ADRs. Then list the subagents available in this project and, in five bullets, tell me how you will run milestone M0. Don't change any files yet.
```

Cevap mantıklıysa milestone promptuna geç.

## CLI'da bilmen gereken 10 şey

| Ne | Nasıl |
|---|---|
| Mod değiştir | `Shift+Tab` (auto → manual → accept edits → plan) |
| Durdur / geri al | `Esc` durdurur, `Esc Esc` veya `/rewind` önceki noktaya döner |
| Subagent'ları izle | `/tasks` |
| Bağlamı temizle | `/clear` (milestone'lar arasında) |
| Kullanım | `/usage` |
| Model / effort | `/model`, `/effort high` |
| Önceki oturuma dön | `claude --continue` veya `/resume` |
| Uzun hedef | `/goal <koşul>` |
| Neden bloklandı | `/permissions` → Recently denied |
| Çok satırlı prompt | Metni yapıştır; Enter gönderir |

## Milestone promptu

Her milestone için yapıştır (M0 yerine sıradakini yaz):

```text
We are starting milestone M0 from docs/PLAN.md.

1. Delegate a design pass to the architect subagent. Give it the milestone's acceptance criteria and ask for interfaces, a task list with owners and disjoint directories, risks and cost impact.
2. Show me the plan summary and wait for my "go".
3. After I approve: delegate the tasks to the owning specialists, running independent tasks in parallel in their worktrees. Each delegation prompt must be self-contained (goal, owned dirs, acceptance criteria, relevant ADRs).
4. After each task: test-runner verifies, reviewer reviews the diff, and security-auditor also reviews anything touching payments, wallets, budgets or auth. Send blocking findings back to the owner until resolved.
5. Merge the worktrees, run `make test && make lint`, update docs/PROGRESS.md, and give me a short summary: what was built, how it was verified, cost impact, Spring notes, and open questions.

Do not push to remote. Stop and ask me before anything listed in CLAUDE.md "Stop and ask".
```

Plan onayından sonra oturum saatlerce kendi başına çalışabilir. `/tasks` ile ilerlemeyi görürsün; istersen Desktop'ta aynı repo'yu WSL oturumuyla açıp diff'leri orada incele.

## Her milestone sonunda senin işin (30–60 dk)

1. `git log` ve diff'leri oku; anlamadığın her şeyi sor: "Why did you choose X over Y here?"
2. Demoyu kendin çalıştır (`make up`, sonra ilgili endpoint/ekran).
3. Raporun "Spring notes" kısmından 3–5 maddelik mülakat notu yaz (`docs/interview-notes.md`, istersen gitignore'da tut).
4. `git push` (sana sorulacak), sonra `/clear` ve sıradaki milestone.

## Maliyet notları

- Claude Code kullanımı aboneliğinden düşer; subagent'lar da aynı limiti kullanır. Limit daralırsa ana oturumu `/model opusplan` yap.
- Tek bir servise dokunan bağımsız işleri cloud oturumuna gönderebilirsin (`claude --cloud`, $100 kredi). Cloud ortamına önce `scripts/cloud-setup.sh`'ı setup script olarak ekle.
- Uygulamanın LLM harcaması `.env`'deki `LLM_DAILY_CAP_USD` ile sınırlı.
- AWS'yi sadece sen, manuel workflow ile açarsın; kit Claude'un `terraform apply` çalıştırmasını engelliyor.
