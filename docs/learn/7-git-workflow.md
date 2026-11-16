# 7. Git workflow: pull requests, protected main, deploy keys

Since 2026-11-15, nobody can push straight to `main` anymore. This chapter explains why, how a
change gets in now, and the two setup steps that made it possible (called A and B when we did them).

## Branch, pull request, main

| Term | Meaning |
|---|---|
| **`main`** | the branch Flux deploys. Whatever is on `main` runs in the cluster within minutes |
| **branch** | a separate line of commits, e.g. `fix-notes-header`. Nothing on it is deployed |
| **pull request (PR)** | a request on GitHub: "please merge my branch into `main`". GitHub runs the **checks** on it first |
| **required check** | a CI job that must pass before the PR can be merged. Here: `config-and-manifests`, `ansible`, `secrets` (the `validate` workflow) |
| **merge (squash)** | the branch's commits become one commit on `main`; then Flux deploys it |

## Why protect `main` (step B)

Before, every `git push` to `main` was deployed at once, even if a check was going to fail a minute
later. With the protection (a **ruleset** on GitHub):

- changes reach `main` **only through a pull request**,
- the PR can be merged **only when the required checks pass** (a typo in a manifest, an invalid
  site setting or a leaked secret is caught *before* it deploys),
- nobody can **force-push** (rewrite history) or **delete** `main`.

The ruleset is kept as code in [.github/rulesets/main.json](../../.github/rulesets/main.json) and was
applied once with:

```bash
gh api -X POST repos/hienmv/anotherhomelab/rulesets --input .github/rulesets/main.json
```

Who may skip the rules (**bypass**):

| Who | Why |
|---|---|
| **deploy keys** | CI's tag bumps (below) and the workstation must keep working |
| **repository admins (you), via a PR only** | an emergency: merge a PR without waiting for the checks. A direct push is still refused |

## Why CI got its own key (step A)

After building an image, CI commits the new version into `k8s/apps/<app>/kustomization.yaml` on
`main` (`deploy(notes): sha-… [skip ci]`); that commit is what Flux deploys. With `main` protected,
that push would be refused, and new images would never be deployed.

GitHub lets **deploy keys** bypass a ruleset on personal repositories (it does not allow GitHub
Actions itself). So the job got a key of its own:

| Command (run once, on the Mac) | What it does |
|---|---|
| `umask 077; ssh-keygen -q -t ed25519 -N "" -C "github-actions bump-tag" -f /tmp/ci_key` | new key pair; only you can read the files |
| `gh secret set CI_DEPLOY_KEY -R hienmv/anotherhomelab < /tmp/ci_key` | the **private** key becomes an encrypted **Actions secret**: workflows can use it, nobody can read it back |
| `gh repo deploy-key add /tmp/ci_key.pub -R hienmv/anotherhomelab --title ci-bump-tag --allow-write` | the **public** key may push to **this repo only** |
| `rm -P /tmp/ci_key /tmp/ci_key.pub` | delete the local copies: the key now exists only inside GitHub |

`bump-tag.yml` checks out with that key (`ssh-key: ${{ secrets.CI_DEPLOY_KEY }}`), and the app
workflows pass the secret to it (`secrets: inherit`). If the key ever leaks: delete `ci-bump-tag`
under Settings → Deploy keys, and repeat the four commands.

### All deploy keys of the repo

| Title | Access | Used by |
|---|---|---|
| `flux homelab-k3s` | read-only | Flux, to pull the repo into the cluster |
| `homelab-workstation-admin` | read + write | `ops@node-workstation` (`git pull`, and push if ever needed) |
| `ci-bump-tag` | read + write | the `bump-tag` CI job |

## How a change gets in now

```bash
git switch -c fix-notes-header                 # 1. a branch
# ... edit, then:
git commit -am "fix(notes): ..."               # 2. commit
git push -u origin fix-notes-header            # 3. push the branch (not main)
gh pr create --fill                            # 4. open the PR: the checks start
gh pr checks --watch                           # 5. wait (about 1-2 minutes)
gh pr merge --squash --delete-branch           # 6. merge: now Flux deploys it
git switch main && git pull                    # 7. back to an up-to-date main
```

On GitHub you can do steps 4-6 with buttons instead.

If you try `git push origin main` anyway:

```
remote: error: GH013: Repository rule violations found for refs/heads/main.
remote: - Changes must be made through a pull request.
 ! [remote rejected] main -> main (push declined due to repository rule violations)
```

That is the protection working, not an error in your setup.

## Secrets and a public repo

The repo is **public** (decided 2026-11-16), so anyone can read every commit, forever. Two guards:

| Guard | When | What happens |
|---|---|---|
| GitHub **push protection** | on `git push` | a push containing a known token type (GitHub, AWS, Cloudflare, ...) is **refused** before it lands |
| **gitleaks** (required check `secrets`) | on every PR | scans the whole history; a leak blocks the merge |

If a secret ever lands anyway: **rotate it first** (new token, delete the old one). Removing it from
git afterwards does not help, it has already been seen.

## The first PR (2026-11-15), as an example

PR #1: a docs change plus a comment in `apps/notes/nginx.conf`. The checks passed, it was merged as
`a3fcbb1`, the notes image was built, and CI's tag bump `7e252c7 deploy(notes): sha-a3fcbb1` landed on
the protected `main` with the `ci-bump-tag` key, which proved the whole path end to end.

Back to: [README](README.md)
