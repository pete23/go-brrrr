-- Full scan, scalar aggregates
SELECT count(*) AS n, sum(charge) AS total_charge, sum(units) AS total_units,
       min(charge) AS min_charge, max(charge) AS max_charge
FROM fact
