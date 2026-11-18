-- Top products, with their governor's level-2 org (fact -> product -> org -> org)
SELECT p.product_id, p.name, g2.name AS governor_l2, sum(f.charge) AS charge
FROM fact f
JOIN product p ON p.product_id = f.product_id
JOIN org g     ON g.org_id = p.governor_id
JOIN org g2    ON g2.org_id = g.l2
GROUP BY p.product_id, p.name, g2.name
ORDER BY charge DESC, p.product_id
LIMIT 20
