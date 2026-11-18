-- Governor level 1 vs supplier level 1 where they differ
SELECT g.l1 AS governor_l1, o.l1 AS supplier_l1, count(*) AS n, sum(f.charge) AS charge
FROM fact f
JOIN product p ON p.product_id = f.product_id
JOIN org g     ON g.org_id = p.governor_id
JOIN org o     ON o.org_id = f.supplier_id
WHERE g.l1 <> o.l1
GROUP BY g.l1, o.l1
ORDER BY g.l1, o.l1
