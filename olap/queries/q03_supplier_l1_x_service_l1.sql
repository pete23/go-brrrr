-- Two hierarchies at once: supplier org level 1 x service level 1
SELECT o.l1 AS supplier_l1, s.l1 AS service_l1, count(*) AS n, sum(f.charge) AS charge
FROM fact f
JOIN org o     ON o.org_id = f.supplier_id
JOIN service s ON s.service_id = f.service_id
GROUP BY o.l1, s.l1
ORDER BY o.l1, s.l1
