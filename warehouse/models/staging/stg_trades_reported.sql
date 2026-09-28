select isin, trade_date, instrument_class, total_trade_value_lakh as face_lakh, wap, way from {{ silver('trades_reported') }}
