-- Walk a governor subtree with a recursive CTE (parent_id), not the flattened columns
WITH RECURSIVE sub AS (
  SELECT org_id FROM org WHERE org_id = 5
  UNION ALL
  SELECT o.org_id FROM org o JOIN sub ON o.parent_id = sub.org_id
)
SELECT p.category, count(*) AS n, sum(f.charge) AS charge
FROM fact f
JOIN product p ON p.product_id = f.product_id
WHERE p.governor_id IN (SELECT org_id FROM sub)
GROUP BY p.category
ORDER BY p.category
