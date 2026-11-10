#!/bin/bash
# Assembles the static Volt homepage into site/dist/. Bash, coreutils and python3 (for the variant check);
# no network access.
# Downloads link to GitHub Releases, so no APK is published from this site.
#
# Output (see site/README.md, "Variants"):
#   dist/variants/<name>/  one complete page per homepage variant: "original" from src/, and every
#                          directory under variants/. nginx serves the live one at / and each at /preview/<name>/.
#   dist/variants.json     name, title and a one-line description of each variant, for the Workbench.
#   dist/cards/            the variants' link-preview images, named <sha256>.<ext> (only when a variant has
#                          one). deploy-release.sh adds them to the server's append-only card store.
#   dist/ (root)           today's page again, byte for byte the same as dist/variants/original/, so
#                          tools that read dist/index.html and older nginx configurations keep working.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/.."
src="$here/src"
variants_src="$here/variants"
dist="$here/dist"

# Files every variant gets from src/ unless it ships its own copy at the same path (img/*.webp and the
# fonts and notices are added below).
shared_files=(favicon.svg favicon-32.png apple-touch-icon.png icon-192.png icon-512.png og.png
              site.webmanifest robots.txt sitemap.xml)

# The download line names the app's own version, so the page can't fall behind a release.
version="$(sed -n 's/.*android:versionName="\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' "$repo/AndroidManifest.xml" | head -n 1)"
[ -n "$version" ] || { echo "No versionName in AndroidManifest.xml" >&2; exit 1; }

# Shared files that don't come from src/ directly: screenshots, the app's Saira fonts and their notices.
add_common() {
  local out="$1"
  mkdir -p "$out/fonts" "$out/img"
  cp "$src"/img/*.webp "$out/img/"
  for weight in regular medium semibold bold; do
    cp "$repo/res/font/saira_$weight.ttf" "$out/fonts/saira-$weight.ttf"
  done
  {
    printf 'Volt homepage: third-party notices\n\n'
    printf '=== Saira typeface (regular, medium, semibold, bold) ===\n'
    cat "$repo/licenses/Saira-OFL.txt"
  } > "$out/THIRD-PARTY-NOTICES.txt"
}

# index.html with @VERSION@ filled in. $1 is the source page, $2 the output, $3 a name for messages.
fill_version() {
  grep -q '@VERSION@' "$1" || { echo "$3: index.html has no @VERSION@ placeholder" >&2; exit 1; }
  sed "s/@VERSION@/$version/g" "$1" > "$2"
  if grep -q '@VERSION@' "$2"; then echo "$3: unreplaced @VERSION@ in index.html" >&2; exit 1; fi
}

# Today's page, exactly as it was built before variants existed.
build_original() {
  local out="$1"
  mkdir -p "$out"
  for file in styles.css main.js "${shared_files[@]}"; do
    cp "$src/$file" "$out/$file"
  done
  fill_version "$src/index.html" "$out/index.html" original
  add_common "$out"
}

# A variant from variants/<name>/: the shared files first, then every regular file it ships (keeping
# subpaths, skipping *.md and dotfiles), then index.html with the version filled in.
build_variant() {
  local name="$1" from="$2" out="$3"
  [ -f "$from/index.html" ] && [ ! -L "$from/index.html" ] || { echo "$name: variants/$name has no index.html" >&2; exit 1; }
  # One robots.txt for the whole site, so no variant can hide previews (or the live page) from crawlers.
  [ ! -e "$from/robots.txt" ] && [ ! -L "$from/robots.txt" ] ||
    { echo "$name: variants may not ship robots.txt; the shared src/robots.txt is used (site/README.md, \"Variants\")" >&2; exit 1; }
  mkdir -p "$out"
  for file in "${shared_files[@]}"; do
    cp "$src/$file" "$out/$file"
  done
  add_common "$out"
  # Symbolic links, sockets and the like would not survive packaging, so they stop the build.
  local odd
  odd="$(cd "$from" && find . ! -type f ! -type d -print | head -n 1)"
  [ -z "$odd" ] || { echo "$name: not a regular file: variants/$name/${odd#./}" >&2; exit 1; }
  (cd "$from" && find . -type f ! -iname '*.md' ! -path '*/.*' -print) | LC_ALL=C sort | while IFS= read -r file; do
    file="${file#./}"
    mkdir -p "$out/$(dirname "$file")"
    cp "$from/$file" "$out/$file"
  done
  fill_version "$from/index.html" "$out/index.html" "$name"
}

# Workbench titles. Unknown names get their own name, capitalized, and a generic line.
variant_title() {
  case "$1" in
    original) echo "Original" ;;
    refined) echo "Refined" ;;
    hybrid) echo "Hybrid" ;;
    app) echo "App look" ;;
    *) printf '%s%s\n' "$(printf '%s' "${1:0:1}" | tr '[:lower:]' '[:upper:]')" "${1:1}" ;;
  esac
}
variant_description() {
  case "$1" in
    original) echo "Today's page, unchanged." ;;
    refined) echo "Today's page, refined: same identity, screenshots and lightning background." ;;
    hybrid) echo "Today's layout and screenshots, rebuilt in the Volt app's design language." ;;
    app) echo "A page in the Volt app's own design language." ;;
    *) echo "A homepage variant from site/variants/$1/." ;;
  esac
}

rm -rf "$dist"
mkdir -p "$dist/variants"
names=(original)
build_original "$dist/variants/original"
if [ -d "$variants_src" ]; then
  for dir in "$variants_src"/*; do
    [ -e "$dir" ] || continue
    name="$(basename "$dir")"
    if [ -L "$dir" ] || [ ! -d "$dir" ]; then
      echo "variants/$name: expected a directory" >&2; exit 1
    fi
    # Names become URL paths and nginx/switch allowlist entries: lowercase letters, digits and hyphens.
    [[ "$name" =~ ^[a-z][a-z0-9-]{0,31}$ ]] || { echo "variants/$name: use lowercase letters, digits and hyphens" >&2; exit 1; }
    [ "$name" != original ] || { echo "variants/original: today's page comes from src/, not variants/" >&2; exit 1; }
    build_variant "$name" "$dir" "$dist/variants/$name"
    names+=("$name")
  done
fi

# Every built page must meet the variant contract (tools/check-variants.py: relative URLs, the CSP,
# link-preview image URLs under /preview/<name>/ with the image's hash, robots). Any problem stops the build.
if ! check_out="$(python3 -I "$here/tools/check-variants.py" "$dist"/variants/* 2>/dev/null)"; then
  printf '%s\n' "$check_out" >&2
  echo "Variant check failed; see site/README.md, \"Variants\"." >&2
  exit 1
fi
python3 -I "$here/tools/check-variants.py" --collect-cards "$dist" 2>/dev/null

# Known variants first, in the owner's order, then any others alphabetically (the glob above is sorted).
ordered=()
for name in original refined hybrid app; do
  for have in "${names[@]}"; do [ "$have" = "$name" ] && ordered+=("$name"); done
done
for have in "${names[@]}"; do
  case "$have" in original|refined|hybrid|app) ;; *) ordered+=("$have") ;; esac
done
{
  printf '{\n  "format": "volt-homepage-variants-v1",\n  "version": "%s",\n  "variants": [\n' "$version"
  count=0
  for name in "${ordered[@]}"; do
    count=$((count + 1))
    sep=","; [ "$count" -eq "${#ordered[@]}" ] && sep=""
    printf '    {"name": "%s", "title": "%s", "description": "%s"}%s\n' \
      "$name" "$(variant_title "$name")" "$(variant_description "$name")" "$sep"
  done
  printf '  ]\n}\n'
} > "$dist/variants.json"

# The root copy of today's page, for older tools and the nginx configuration before variants.
(cd "$dist/variants/original" && find . -type f -print) | while IFS= read -r file; do
  file="${file#./}"
  mkdir -p "$dist/$(dirname "$file")"
  cp "$dist/variants/original/$file" "$dist/$file"
done

# Publication manifest, kept beside dist/ rather than served. (sha256sum where shasum is missing; same format.)
sha256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1"; else sha256sum "$1"; fi; }
(cd "$dist" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | while read -r file; do sha256 "$file"; done) > "$here/dist.sha256"
echo "Built $dist ($(wc -l < "$here/dist.sha256" | tr -d ' ') files; variants: ${ordered[*]})"
