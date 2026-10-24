-- Apply once to cultural_tourism_group_buy after checking SHOW INDEX.
-- No transaction durability settings or order semantics are changed.
ALTER TABLE group_buy_order_list
  ADD INDEX idx_team_status_out_trade (team_id, status, out_trade_no),
  ADD INDEX idx_user_out_trade_status (user_id, out_trade_no, status);
