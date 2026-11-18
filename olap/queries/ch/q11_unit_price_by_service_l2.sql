-- Ratio of aggregates: effective price per unit by service level 2, top 10
-- ClickHouse: Decimal / integer keeps the dividend's scale (2), so divide as Float64.
SELECT s.l2 AS service_l2,
       round(toFloat64(sum(f.charge)) / sum(f.units), 4) AS per_unit,
       count(*) AS n
FROM fact f
JOIN service s ON s.service_id = f.service_id
GROUP BY s.l2
ORDER BY per_unit DESC, s.l2
LIMIT 10
