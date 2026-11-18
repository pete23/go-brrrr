-- Filter to one supplier subtree (org level 1 = 3), break down by service level 2
SELECT s.l2 AS service_l2, count(*) AS n, sum(f.charge) AS charge, sum(f.units) AS units
FROM fact f
JOIN org o     ON o.org_id = f.supplier_id
JOIN service s ON s.service_id = f.service_id
WHERE o.l1 = 3
GROUP BY s.l2
ORDER BY s.l2
