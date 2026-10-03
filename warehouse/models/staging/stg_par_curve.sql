select as_of, tenor_years, par_yield_semiannual, par_yield_annualised from {{ silver('par_curve') }}
