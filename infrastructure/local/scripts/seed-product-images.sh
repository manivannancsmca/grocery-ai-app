#!/usr/bin/env bash
# =====================================================================================
# Local dev: give every product that has no photo a real one, chosen from its name (category as a fallback).
#
# The photos are frontend/grocery-web/public/images/products/<slug>.jpg (credits in CREDITS.md there), served
# by the frontend at /images/products/. The catalog stores absolute http(s) URLs, so the script writes
# $PRODUCT_IMAGE_BASE_URL<slug>.jpg (default: the Vite dev server on http://localhost:6420).
#
# How it stays consistent with the catalog's rules (see SnapshotPublisher):
#   1. one transaction: the products' rows are locked by the UPDATE that raises product_version (and the JPA
#      version), then the image rows are inserted — a product that already has a photo is never touched;
#   2. POST /api/admin/catalog/republish re-sends every snapshot with its current version, so the search index
#      takes the raised versions (and the photos) and keeps everything else as it is.
#
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog up -d
# Run (Git Bash):  bash infrastructure/local/scripts/seed-product-images.sh [--dry-run]
# Safe to re-run: products created later (e.g. by smoke tests) get a photo, the rest are left alone.
# =====================================================================================
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
BASE_URL="${PRODUCT_IMAGE_BASE_URL:-http://localhost:6420/images/products/}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-grocery-mysql-1}"
CATALOG_DB_USER="${CATALOG_DB_USER:-catalog_user}"
CATALOG_DB_PASSWORD="${CATALOG_DB_PASSWORD:-catalog_pass}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
IMAGE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)/frontend/grocery-web/public/images/products"
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

sql() { docker exec -i -e MYSQL_PWD="$CATALOG_DB_PASSWORD" "$MYSQL_CONTAINER" mysql -u"$CATALOG_DB_USER" --batch catalog_db; }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

# Products without a photo and the photo each one gets. Order matters: specific names first, then generic
# "dal" / "rice" (name or category), then a staples photo for names that say nothing (test data such as "dbg").
TARGETS_SQL="
SELECT p.id,
       CASE
         WHEN n LIKE '%basmati%' THEN 'basmati-rice'
         WHEN n LIKE '%sona mas%' OR n LIKE '%masoori%' OR n LIKE '%masuri%' THEN 'sona-masoori-rice'
         WHEN n LIKE '%toor%' OR n LIKE '%tur dal%' OR n LIKE '%arhar%' THEN 'toor-dal'
         WHEN n LIKE '%moong%' OR n LIKE '%mung%' THEN 'moong-dal'
         WHEN n LIKE '%urad%' THEN 'urad-dal'
         WHEN n LIKE '%masoor%' OR n LIKE '%lentil%' THEN 'masoor-dal'
         WHEN n LIKE '%chana%' OR n LIKE '%chickpea%' THEN 'chana-dal'
         WHEN n LIKE '%besan%' OR n LIKE '%gram flour%' THEN 'besan'
         WHEN n LIKE '%atta%' OR n LIKE '%wheat flour%' THEN 'atta'
         WHEN n LIKE '%sooji%' OR n LIKE '%suji%' OR n LIKE '%rava%' OR n LIKE '%semolina%' THEN 'semolina'
         WHEN n LIKE '%poha%' THEN 'poha'
         WHEN n LIKE '%jaggery%' THEN 'jaggery'
         WHEN n LIKE '%curd%' OR n LIKE '%dahi%' OR n LIKE '%yogurt%' OR n LIKE '%yoghurt%' THEN 'curd'
         WHEN n LIKE '%tomato%' THEN 'tomato'
         WHEN n LIKE '%dal%' OR c LIKE '%pulse%' THEN 'toor-dal'
         WHEN n LIKE '%rice%' OR c LIKE '%rice%' THEN 'sona-masoori-rice'
         ELSE 'staples'
       END AS slug
FROM (SELECT p.id, LOWER(p.name) AS n, LOWER(COALESCE(c.name, '')) AS c
      FROM product p LEFT JOIN category c ON c.id = p.category_id
      WHERE NOT EXISTS (SELECT 1 FROM product_image i WHERE i.product_id = p.id)) p"

echo "== Products without a photo, by the photo they get"
summary=$(printf '%s\n' "SELECT slug, COUNT(*) FROM ($TARGETS_SQL) t GROUP BY slug ORDER BY slug;" | sql | tail -n +2)
if [ -z "$summary" ]; then
  echo "  every product already has a photo — nothing to do"
  exit 0
fi
missing=0
while IFS=$'\t' read -r slug count; do
  if [ -f "$IMAGE_DIR/$slug.jpg" ]; then echo "  $slug.jpg  ← $count product(s)"; else echo "  ✘ $slug.jpg is missing in $IMAGE_DIR ($count product(s))"; missing=1; fi
done <<< "$summary"
[ "$missing" = 0 ] || { echo "Add the missing photo(s) first."; exit 1; }
$DRY_RUN && { echo "Dry run: nothing changed."; exit 0; }

echo "== Writing photos (one transaction)"
updated=$(sql <<SQL | tail -n 1
START TRANSACTION;
CREATE TEMPORARY TABLE seed_target (id BINARY(16) PRIMARY KEY, slug VARCHAR(40) NOT NULL);
INSERT INTO seed_target (id, slug) $TARGETS_SQL;
-- Locks the rows first and raises the snapshot version (search external version) and the JPA version.
UPDATE product p JOIN seed_target t ON t.id = p.id
   SET p.product_version = p.product_version + 1, p.version = p.version + 1, p.updated_at = UTC_TIMESTAMP(6);
INSERT INTO product_image (product_id, sort_order, url)
SELECT t.id, 0, CONCAT('$BASE_URL', t.slug, '.jpg') FROM seed_target t
 WHERE NOT EXISTS (SELECT 1 FROM product_image i WHERE i.product_id = t.id);
SELECT ROW_COUNT();
COMMIT;
SQL
)
echo "  $updated product(s) got a photo ($BASE_URL<slug>.jpg)"

echo "== Rebuilding the search documents (admin republish)"
token=$(curl -s -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | json accessToken)
[ -n "$token" ] || { echo "  ✘ admin login failed — run the republish from Admin → Catalog → Search index instead"; exit 1; }
result=$(curl -s -w ' %{http_code}' -X POST "$GW/api/admin/catalog/republish" -H "Authorization: Bearer $token")
case "$result" in
  *" 200") echo "  republished: ${result% 200}" ;;
  *) echo "  ✘ republish failed: $result"; exit 1 ;;
esac

echo "== Waiting for the search index"
for _ in $(seq 1 60); do
  # grep finds nothing until the index catches up; that must not end the script (set -e / pipefail).
  shown=$(curl -s "$GW/api/search/products?size=50" | { grep -o '"primaryImageUrl":"http[^"]*"' || true; } | wc -l)
  if [ "$shown" -gt 0 ]; then echo "  ✔ search results now carry photos ($shown on the first page)"; exit 0; fi
  sleep 1
done
echo "  ✘ no photo in search results after 60 s — check catalog-query-service and the Debezium connector"
exit 1
