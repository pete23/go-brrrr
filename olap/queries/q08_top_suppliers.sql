-- High-cardinality group by (100k suppliers), top-N
SELECT supplier_id, count(*) AS n, sum(charge) AS charge
FROM fact
GROUP BY supplier_id
ORDER BY charge DESC, supplier_id
LIMIT 100
