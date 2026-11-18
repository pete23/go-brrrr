-- Very high-cardinality group by (product x supplier pairs), top-N
SELECT product_id, supplier_id, count(*) AS n, sum(charge) AS charge
FROM fact
GROUP BY product_id, supplier_id
ORDER BY charge DESC, product_id, supplier_id
LIMIT 10
