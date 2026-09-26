#!/usr/bin/env bash
# ============================================================
# pre-commit secret scanner  (tools/pre-commit-hook.sh)
#
# Install:
#   cp tools/pre-commit-hook.sh .git/hooks/pre-commit
#   chmod +x .git/hooks/pre-commit
#
# Blocks the commit when staged content looks like a real secret.
# Bypass (only when you are sure it is a false positive):
#   git commit --no-verify
#
# NOTE: messages are intentionally ASCII-only so the hook behaves
# the same regardless of the terminal locale / code page.
# ============================================================
set -e

# Placeholders and doc examples are not treated as secrets.
SAFE_CONTENT='change-me|your[-_]|sk-xxx|sk-your|sk-no-key-set|example|placeholder|XXXX'

# The scanner itself contains the regex patterns it searches for, so it
# must be skipped, otherwise it would always flag its own definition.
# Must be an array: a plain string would be word-split with the quotes kept
# literally, git would reject the pathspec and the scan would see nothing.
SCAN=(git diff --cached -U0 --no-color -- . ':(exclude)tools/pre-commit-hook.sh' ':(exclude).git/hooks/pre-commit')

# Read the diff once and fail closed: if git errors out, block the commit
# instead of silently scanning an empty diff.
if ! STAGED="$("${SCAN[@]}")"; then
  echo "[BLOCKED] secret scan could not read the staged diff (see git error above)."
  exit 1
fi
ADDED="$(printf '%s\n' "$STAGED" | grep -E '^\+' | grep -vE '^\+\+\+' || true)"

PATTERNS=(
  'sk-[A-Za-z0-9_-]{20,}'
  'AKIA[0-9A-Z]{16}'
  'LTAI[0-9A-Za-z]{12,}'
  'BEGIN [A-Z ]*PRIVATE KEY'
)

FOUND=0
for pat in "${PATTERNS[@]}"; do
  matches="$(printf '%s\n' "$ADDED" \
    | grep -vE "$SAFE_CONTENT" \
    | grep -nE "$pat" || true)"
  if [ -n "$matches" ]; then
    echo "[BLOCKED] possible secret detected in staged content:"
    echo "$matches" | head -5
    FOUND=1
  fi
done

# Hard-coded passwords are only checked in config files.
# Matches `password: value`, `password=value` and `password: ${VAR:default}`;
# the `${VAR:}` form (no default) is legitimate and must not be flagged.
PWD_RE='(password|passwd|pwd)[[:space:]]*[:=][[:space:]]*(\$\{[A-Za-z_]+:([^}]*)\}|([^[:space:]#]+))'
if ! PWD_DIFF="$(git diff --cached -U0 --no-color -- '*.yml' '*.yaml' '*.properties')"; then
  echo "[BLOCKED] secret scan could not read the staged config diff."
  exit 1
fi
pwd_hits="$(printf '%s\n' "$PWD_DIFF" \
  | grep -E '^\+' \
  | grep -vE '^\+\+\+' \
  | grep -vE '^\+[[:space:]]*#' \
  | grep -vE "$SAFE_CONTENT" \
  | grep -nE "$PWD_RE" \
  | grep -vE '[:=][[:space:]]*\$\{[A-Za-z_]+:\}[[:space:]]*$' || true)"
if [ -n "$pwd_hits" ]; then
  echo "[BLOCKED] hard-coded password found in config file:"
  echo "$pwd_hits" | head -5
  FOUND=1
fi

if [ "$FOUND" -eq 1 ]; then
  echo
  echo "Move the secret into .env (already git-ignored) and read it"
  echo "from an environment variable instead."
  echo "If this is a false positive, use: git commit --no-verify"
  exit 1
fi

exit 0
