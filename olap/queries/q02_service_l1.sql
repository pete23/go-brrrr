-- Roll up to the top of the service hierarchy (flattened ancestors)
SELECT s1.service_id, s1.name, count(*) AS n, sum(f.charge) AS charge, sum(f.units) AS units
FROM fact f
JOIN service s  ON s.service_id = f.service_id
JOIN service s1 ON s1.service_id = s.l1
GROUP BY s1.service_id, s1.name
ORDER BY s1.service_id
