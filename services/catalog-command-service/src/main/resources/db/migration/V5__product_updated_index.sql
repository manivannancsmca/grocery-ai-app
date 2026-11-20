-- grocery-common 0.10.0 exports: the admin product list and its export read every vendor's products newest change
-- first (updated_at DESC, id DESC). The export reads in keyset batches ("after the last row seen"), which stay fast
-- only when an index serves that order; idx_product_vendor_status_updated starts with vendor_id, so it does not.
-- InnoDB secondary indexes end with the primary key, so (updated_at) also serves the id tie-breaker.
-- Online on MySQL 8.4 (ALGORITHM=INPLACE, LOCK=NONE): reads and writes go on while it builds.
CREATE INDEX idx_product_updated ON product (updated_at) ALGORITHM = INPLACE LOCK = NONE;
