-- Exact distinct counts per service level 1
SELECT s.l1 AS service_l1, count(DISTINCT f.product_id) AS products,
       count(DISTINCT f.supplier_id) AS suppliers
FROM fact f
JOIN service s ON s.service_id = f.service_id
GROUP BY s.l1
ORDER BY s.l1
