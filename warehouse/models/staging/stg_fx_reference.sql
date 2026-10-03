select as_of, currency, inr_per_unit from {{ silver('fx_reference') }}
