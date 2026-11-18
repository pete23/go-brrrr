-- Window function over an aggregate: top 3 leaf services within each service level 1
SELECT service_l1, service_id, charge, rnk
FROM (
  SELECT s.l1 AS service_l1, f.service_id, sum(f.charge) AS charge,
         rank() OVER (PARTITION BY s.l1 ORDER BY sum(f.charge) DESC, f.service_id) AS rnk
  FROM fact f
  JOIN service s ON s.service_id = f.service_id
  GROUP BY s.l1, f.service_id
) t
WHERE rnk <= 3
ORDER BY service_l1, rnk
