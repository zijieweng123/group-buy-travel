USE cultural_tourism_group_buy;

CREATE TABLE IF NOT EXISTS ticket_calendar_inventory (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL COMMENT '拼团活动ID',
  goods_id VARCHAR(32) NOT NULL COMMENT '票种/商品ID',
  travel_date DATE NOT NULL COMMENT '游玩日期',
  available_stock INT NOT NULL COMMENT '当日可售库存',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0停售 1可售',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_activity_goods_date (activity_id, goods_id, travel_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票务日历库存';

ALTER TABLE group_buy_order_list
  ADD COLUMN travel_date DATE NULL COMMENT '游玩日期' AFTER goods_id,
  ADD UNIQUE KEY uq_user_ticket_date (activity_id, goods_id, travel_date, user_id, out_trade_no);

INSERT INTO ticket_calendar_inventory(activity_id, goods_id, travel_date, available_stock)
VALUES (100123, '9890001', CURRENT_DATE + INTERVAL 1 DAY, 100)
ON DUPLICATE KEY UPDATE available_stock = VALUES(available_stock), status = 1;
