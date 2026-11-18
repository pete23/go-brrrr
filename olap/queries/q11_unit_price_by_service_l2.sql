-- Ratio of aggregates: effective price per unit by service level 2, top 10
SELECT s.l2 AS service_l2,
       round(sum(f.charge) / sum(f.units), 4) AS per_unit,
       count(*) AS n
FROM fact f
JOIN service s ON s.service_id = f.service_id
GROUP BY s.l2
ORDER BY per_unit DESC, s.l2
LIMIT 10
