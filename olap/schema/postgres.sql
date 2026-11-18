-- Postgres 18 and pgrust (heap). {{FACT_USING}} is replaced by the runner:
-- empty for heap, "USING pgrcolumnar" for pgrust's columnar store.
-- Hierarchies: parent_id for recursive walks, l1..l4 = ancestor at each level
-- (the node itself at its own level, 0 below it).
CREATE TABLE org (
  org_id    integer PRIMARY KEY,
  parent_id integer NOT NULL,
  level     smallint NOT NULL,
  name      text NOT NULL,
  l1 integer NOT NULL, l2 integer NOT NULL, l3 integer NOT NULL, l4 integer NOT NULL
);
CREATE TABLE service (
  service_id integer PRIMARY KEY,
  parent_id  integer NOT NULL,
  level      smallint NOT NULL,
  name       text NOT NULL,
  l1 integer NOT NULL, l2 integer NOT NULL, l3 integer NOT NULL, l4 integer NOT NULL
);
CREATE TABLE product (
  product_id  integer PRIMARY KEY,
  name        text NOT NULL,
  category    text NOT NULL,
  governor_id integer NOT NULL   -- -> org (level 3)
);
CREATE TABLE fact (
  product_id  integer NOT NULL,  -- -> product
  supplier_id integer NOT NULL,  -- -> org (leaf)
  charge      numeric(12,2) NOT NULL,
  service_id  integer NOT NULL,  -- -> service (leaf)
  units       integer NOT NULL
) {{FACT_USING}};
CREATE INDEX ON org (parent_id);
CREATE INDEX ON service (parent_id);
