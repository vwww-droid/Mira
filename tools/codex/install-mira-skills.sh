#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
skills_src="$repo_root/skills"
skills_dst="$repo_root/.codex/skills"

mkdir -p "$skills_dst"

installed=0
skipped=0

for skill_dir in "$skills_src"/*; do
  [ -d "$skill_dir" ] || continue
  [ -f "$skill_dir/SKILL.md" ] || continue

  name=$(basename -- "$skill_dir")
  dest="$skills_dst/$name"
  rel_target="../../skills/$name"

  if [ -L "$dest" ]; then
    current=$(readlink "$dest")
    if [ "$current" = "$rel_target" ]; then
      printf 'ok %s -> %s\n' "$dest" "$rel_target"
      continue
    fi
    rm "$dest"
  elif [ -e "$dest" ]; then
    printf 'skip %s: destination already exists and is not a symlink\n' "$dest" >&2
    skipped=$((skipped + 1))
    continue
  fi

  ln -s "$rel_target" "$dest"
  printf 'link %s -> %s\n' "$dest" "$rel_target"
  installed=$((installed + 1))
done

printf 'done installed=%s skipped=%s skills_dst=%s\n' "$installed" "$skipped" "$skills_dst"
