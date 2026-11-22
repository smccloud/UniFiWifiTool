#!/usr/bin/env bash
#
# Increment the application version and keep every mirrored reference in sync.
#
# Override the amount with the BUMP_AMOUNT environment variable:
#   BUMP_AMOUNT=0.0.1 ./scripts/bump-version.sh   # patch bump
#   BUMP_AMOUNT=0.1.0 ./scripts/bump-version.sh   # minor bump (default)
#   BUMP_AMOUNT=1.0.0 ./scripts/bump-version.sh   # major bump
#
# A non-zero higher-order component resets lower-order components, so
# 0.5.1 + 0.1.0 -> 0.6.0 and 0.5.1 + 1.0.0 -> 1.0.0.
#
# After updating, the script commits the changed version files. On a protected
# branch (the remote's default branch, e.g. master) it creates a
# `release-<version>` branch, pushes that, and opens a pull request with `gh`.
# On any other branch it commits and pushes the current branch directly.
#
# Behaviour is controlled with PR_MODE:
#   PR_MODE=auto    Create a PR when the current branch is the remote default
#                   branch; push directly otherwise (default).
#   PR_MODE=always  Always create a release branch and pull request.
#   PR_MODE=never   Never create a branch; commit and push the current branch.
#
# Branch names can be overridden with RELEASE_BRANCH (default:
# release-<new-version>). The PR target is the branch the script started on;
# set BASE_BRANCH to override it (PR mode only).
#
# Scheduling a weekly bump with cron
# ----------------------------------
# The script is safe to run unattended, but cron starts it with a minimal
# environment and no terminal, so a few things must be true first:
#
#   1. The checkout is on a branch (not a detached HEAD) and PUSH_REMOTE is
#      configured, e.g. `git -C /opt/UnFiWifiTool checkout master`.
#   2. The version files are committed. The script refuses to run if any of
#      them has uncommitted changes, so the working tree must be clean.
#   3. git can push without prompting: use an SSH key with no passphrase or a
#      stored credential helper, and make sure the key is readable by the cron
#      user (HOME must resolve, since cron may not set it).
#   4. `gh` is installed and authenticated (export GH_TOKEN or set up a
#      credential helper for the cron user) if you want pull requests opened
#      automatically. Without `gh`, the release branch is still pushed and the
#      script prints the URL to open the PR.
#   5. Use absolute paths, because cron's PATH usually does not include the
#      directory holding your git/bash binaries.
#
# Example crontab entry (every Monday at 03:00), added with `crontab -e`:
#
#   0 3 * * 1 cd /opt/UnFiWifiTool && HOME=/home/deploy PATH=/usr/local/bin:/usr/bin:/bin GH_TOKEN=... BUMP_AMOUNT=0.0.1 ./scripts/bump-version.sh >> /var/log/unfi-bump-version.log 2>&1
#
# Notes:
#   - Set BUMP_AMOUNT in the cron line (patch is the usual choice for a
#     scheduled run); omitting it defaults to a minor bump.
#   - Keep the log redirect so failures are visible; check that file if the
#     version stops moving.
#   - On a protected default branch, the script opens a PR instead of pushing
#     to it directly. The PR still needs to be reviewed and merged (branch
#     protection cannot be bypassed by this script).
#   - Set PR_MODE=never only if direct pushes to the target branch are allowed.
#   - For shared machines, prefer a system cron file such as /etc/cron.d/unfi
#     (which takes an extra user field) over the invoking user's crontab.
#   - Verify the setup by running the same command once by hand from an empty
#     environment: `env -i /bin/bash -c '<cron command>'`.

set -euo pipefail

BUMP_AMOUNT="${BUMP_AMOUNT:-0.1.0}"
PUSH_REMOTE="${PUSH_REMOTE:-origin}"
PR_MODE="${PR_MODE:-auto}"
BASE_BRANCH="${BASE_BRANCH:-}"
RELEASE_BRANCH="${RELEASE_BRANCH:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT/VERSION"
AFFECTED_FILES=(
  "$VERSION_FILE"
  "$ROOT/frontend/package.json"
  "$ROOT/frontend/package-lock.json"
  "$ROOT/.env.example"
  "$ROOT/docker-compose.yml"
  "$ROOT/backend/pyproject.toml"
  "$ROOT/backend/app/__init__.py"
  "$ROOT/README.md"
  "$ROOT/docs/DESIGN.md"
  "$ROOT/unraid/README.md"
)
CHANGED_FILES=()

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail "git is required"
git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$ROOT is not a git work tree"
branch="$(git -C "$ROOT" branch --show-current)"
[[ -n "$branch" ]] || fail "detached HEAD; check out a branch before bumping"
git -C "$ROOT" remote get-url "$PUSH_REMOTE" >/dev/null 2>&1 || fail "git remote '$PUSH_REMOTE' is not configured"

case "$PR_MODE" in
  auto|always|never) ;;
  *) fail "PR_MODE must be auto, always, or never (got: $PR_MODE)" ;;
esac

default_branch=""
if git -C "$ROOT" symbolic-ref --quiet --short "refs/remotes/$PUSH_REMOTE/HEAD" >/dev/null 2>&1; then
  default_branch="$(git -C "$ROOT" symbolic-ref --short "refs/remotes/$PUSH_REMOTE/HEAD")"
  default_branch="${default_branch#"$PUSH_REMOTE"/}"
elif [[ "$branch" == "master" || "$branch" == "main" ]]; then
  # Some clones lack refs/remotes/<remote>/HEAD; assume the usual default.
  default_branch="$branch"
fi

for file in "${AFFECTED_FILES[@]}"; do
  [[ -f "$file" ]] || fail "missing $file"
  rel="${file#"$ROOT/"}"
  if ! git -C "$ROOT" diff --quiet -- "$rel" || ! git -C "$ROOT" diff --cached --quiet -- "$rel"; then
    fail "uncommitted changes in $rel; commit or stash them first"
  fi
done

old_version="$(tr -d '[:space:]' < "$VERSION_FILE")"
[[ "$old_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "VERSION is not MAJOR.MINOR.PATCH: $old_version"
[[ "$BUMP_AMOUNT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "BUMP_AMOUNT is not MAJOR.MINOR.PATCH: $BUMP_AMOUNT"

IFS=. read -r v_major v_minor v_patch <<< "$old_version"
IFS=. read -r a_major a_minor a_patch <<< "$BUMP_AMOUNT"

new_major=$((v_major + a_major))
new_minor=$((v_minor + a_minor))
new_patch=$((v_patch + a_patch))

# Semantic-version behavior: a major/minor bump resets lower components.
if (( a_major > 0 )); then
  new_minor=0
  new_patch=0
elif (( a_minor > 0 )); then
  new_patch=0
fi

new_version="$new_major.$new_minor.$new_patch"
[[ "$new_version" != "$old_version" ]] || fail "version did not change ($old_version + $BUMP_AMOUNT)"

use_pr=false
case "$PR_MODE" in
  always) use_pr=true ;;
  auto)
    if [[ -n "$default_branch" && "$branch" == "$default_branch" ]]; then
      use_pr=true
    fi
    ;;
esac

base_branch="${BASE_BRANCH:-$branch}"
release_branch="${RELEASE_BRANCH:-release-$new_version}"
if [[ "$use_pr" == true ]]; then
  [[ "$release_branch" != "$base_branch" ]] || fail "RELEASE_BRANCH must differ from BASE_BRANCH ($base_branch)"
  if git -C "$ROOT" show-ref --verify --quiet "refs/heads/$release_branch"; then
    fail "local branch '$release_branch' already exists; delete it or set RELEASE_BRANCH"
  fi
  if git -C "$ROOT" ls-remote --exit-code --heads "$PUSH_REMOTE" "$release_branch" >/dev/null 2>&1; then
    fail "remote branch '$PUSH_REMOTE/$release_branch' already exists; delete it or set RELEASE_BRANCH"
  fi
fi

replace_all() {
  local file="$1"
  local old="$2"
  local new="$3"
  local old_re

  [[ -f "$file" ]] || fail "missing $file"
  if grep -qF "$old" "$file"; then
    old_re="${old//./\\.}"
    sed -i.bak "s|$old_re|$new|g" "$file"
    rm -f "$file.bak"
    CHANGED_FILES+=("$file")
    printf 'updated %s\n' "${file#"$ROOT/"}"
  fi
}

replace_package_lock_versions() {
  local file="$1"
  local old="$2"
  local new="$3"
  local tmp

  [[ -f "$file" ]] || fail "missing $file"
  tmp="$(mktemp)"
  awk -v old="$old" -v new="$new" '
    BEGIN { changed = 0 }
    {
      needle = "\"version\": \"" old "\""
      pos = index($0, needle)
      if (changed < 2 && pos > 0) {
        $0 = substr($0, 1, pos - 1) "\"version\": \"" new "\"" substr($0, pos + length(needle))
        changed++
      }
      print
    }
  ' "$file" > "$tmp"
  cat "$tmp" > "$file"
  rm -f "$tmp"
  CHANGED_FILES+=("$file")
  printf 'updated %s\n' "${file#"$ROOT/"}"
}

replace_all "$VERSION_FILE" "$old_version" "$new_version"
replace_all "$ROOT/frontend/package.json" "\"version\": \"$old_version\"" "\"version\": \"$new_version\""
replace_package_lock_versions "$ROOT/frontend/package-lock.json" "$old_version" "$new_version"
replace_all "$ROOT/.env.example" "IMAGE_VERSION=$old_version" "IMAGE_VERSION=$new_version"
replace_all "$ROOT/docker-compose.yml" "IMAGE_VERSION:-$old_version" "IMAGE_VERSION:-$new_version"
replace_all "$ROOT/backend/pyproject.toml" "version = \"$old_version\"" "version = \"$new_version\""
replace_all "$ROOT/backend/app/__init__.py" "return \"$old_version\"" "return \"$new_version\""
replace_all "$ROOT/README.md" "$old_version" "$new_version"
replace_all "$ROOT/docs/DESIGN.md" "$old_version" "$new_version"
replace_all "$ROOT/unraid/README.md" "$old_version" "$new_version"

printf '\nVersion bumped: %s -> %s (increment %s)\n' "$old_version" "$new_version" "$BUMP_AMOUNT"

((${#CHANGED_FILES[@]} > 0)) || fail "no version references changed"

relative_changed=()
for file in "${CHANGED_FILES[@]}"; do
  relative_changed+=("${file#"$ROOT/"}")
done

if [[ "$use_pr" == true ]]; then
  git -C "$ROOT" checkout -b "$release_branch"
fi

git -C "$ROOT" add -- "${relative_changed[@]}"
git -C "$ROOT" commit -m "Bump version to $new_version" -- "${relative_changed[@]}"

if [[ "$use_pr" == true ]]; then
  git -C "$ROOT" push -u "$PUSH_REMOTE" "$release_branch"

  if command -v gh >/dev/null 2>&1; then
    body_file="$(mktemp)"
    trap 'rm -f "$body_file"' EXIT
    cat > "$body_file" <<EOF
Bumps the application version from \`$old_version\` to **\`$new_version\`** (increment \`$BUMP_AMOUNT\`).

Generated by \`scripts/bump-version.sh\`.

## Files updated

$(printf -- '- `%s`\n' "${relative_changed[@]}")

## Verification

Run the usual checks before merging:

\`\`\`bash
cd backend && .venv/bin/python -m pytest
cd frontend && npm run build
\`\`\`
EOF
    if ! (cd "$ROOT" && gh pr create \
      --base "$base_branch" \
      --head "$release_branch" \
      --title "Bump version to $new_version" \
      --body-file "$body_file"); then
      printf 'ERROR: %s/%s was pushed, but opening the pull request failed.\n' "$PUSH_REMOTE" "$release_branch" >&2
      printf 'Open it from the repository for branch %s.\n' "$release_branch" >&2
      exit 1
    fi
    printf 'Committed %s on %s and opened a pull request into %s\n' \
      "$new_version" "$release_branch" "$base_branch"
  else
    printf 'Branch %s/%s pushed. gh is not installed, so open a pull request into %s manually.\n' \
      "$PUSH_REMOTE" "$release_branch" "$base_branch"
  fi
else
  git -C "$ROOT" push "$PUSH_REMOTE" "$branch"
  printf 'Committed and pushed %s to %s/%s\n' "$new_version" "$PUSH_REMOTE" "$branch"
fi
