CREATE TABLE reservation_units (
    shop_id     BIGINT NOT NULL,
    item_id     BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    id          BIGINT NOT NULL,
    PRIMARY KEY (shop_id, item_id, location_id, id)
) ENGINE = InnoDB;

CREATE TABLE reserved_quantities (
    reservation_id VARCHAR(96) NOT NULL,
    shop_id        BIGINT NOT NULL,
    item_id        BIGINT NOT NULL,
    location_id    BIGINT NOT NULL,
    unit_id        BIGINT NOT NULL,
    PRIMARY KEY (reservation_id, unit_id)
) ENGINE = InnoDB;

CREATE TABLE inventory_ledger (
    shop_id        BIGINT NOT NULL,
    item_id        BIGINT NOT NULL,
    location_id    BIGINT NOT NULL,
    total_quantity BIGINT NOT NULL,
    sold_quantity  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id, item_id, location_id)
) ENGINE = InnoDB;
