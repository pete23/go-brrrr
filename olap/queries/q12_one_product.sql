-- Selective predicate on a fact column (no index), joined to supplier level 2
SELECT o.l2 AS supplier_l2, count(*) AS n, sum(f.charge) AS charge
FROM fact f
JOIN org o ON o.org_id = f.supplier_id
WHERE f.product_id = 76101
GROUP BY o.l2
ORDER BY o.l2
