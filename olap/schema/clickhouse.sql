-- ClickHouse. Fact sort key is tuple() (insertion order, like the heap and
-- pgrcolumnar tables) so no engine gets a free clustering advantage.
CREATE TABLE org (
  org_id UInt32, parent_id UInt32, level UInt8, name String,
  l1 UInt32, l2 UInt32, l3 UInt32, l4 UInt32
) ENGINE = MergeTree ORDER BY org_id;
CREATE TABLE service (
  service_id UInt32, parent_id UInt32, level UInt8, name String,
  l1 UInt32, l2 UInt32, l3 UInt32, l4 UInt32
) ENGINE = MergeTree ORDER BY service_id;
CREATE TABLE product (
  product_id UInt32, name String, category String, governor_id UInt32
) ENGINE = MergeTree ORDER BY product_id;
CREATE TABLE fact (
  product_id UInt32, supplier_id UInt32, charge Decimal(12,2),
  service_id UInt32, units UInt32
) ENGINE = MergeTree ORDER BY tuple();
