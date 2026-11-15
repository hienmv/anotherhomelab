# Repository rulesets (as code)

GitHub settings that protect the repo, kept here so they are reviewed and reproducible.

| File | What |
|---|---|
| `main.json` | `main`: no direct pushes (pull requests only), the `validate` jobs `config-and-manifests`, `ansible`, `secrets` must pass, no force-push, no deletion. Bypass: **deploy keys** (CI's `ci-bump-tag` commits image tags; the workstation's key), and **repository admins via pull request only** (merge without waiting, in an emergency). |

Apply / update (on the Mac, needs `gh` logged in as the repo owner):

```bash
gh api -X POST repos/hienmv/anotherhomelab/rulesets --input .github/rulesets/main.json          # create
gh api repos/hienmv/anotherhomelab/rulesets                                                       # list (id)
gh api -X PUT  repos/hienmv/anotherhomelab/rulesets/<id> --input .github/rulesets/main.json     # update
```

Why deploy keys and not "GitHub Actions" as the exception: GitHub allows the Actions app as a
bypass actor only for organisation repositories; on a personal repo it rejects it.

## Status

Applied 2026-11-15 (ruleset id 27316513). Since then every change goes through a pull request:

```bash
git switch -c <topic>; git commit ...; git push -u origin <topic>
gh pr create --fill          # validate runs on the PR
gh pr merge --squash --auto  # merges by itself once the checks pass
```
