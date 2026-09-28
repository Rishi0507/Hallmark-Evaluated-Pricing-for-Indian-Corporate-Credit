select * from {{ silver('svc_golden') }} where isin is not null
