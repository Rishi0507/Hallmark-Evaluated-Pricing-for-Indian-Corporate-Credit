# web

React and TypeScript dashboard. It reads a static snapshot of the warehouse marts from `public/data`, so it can be hosted on GitHub Pages without a backend.

| View | Contents |
|---|---|
| Overview | Headline results, out-of-sample error against naive baselines, marks by method |
| Pricing | Credit curves by rating for a date, three-year spread by rating over time, marks table with the evidence behind each mark |
| Backtest | Carried marks against baselines, error by method, calibration by confidence, error distribution |
| Reconciliation | Reported against settled bond trades per day, outcomes of every control by instrument class |
| Distribution | Retail denomination flow per month, its yield, flow by rating, rating actions, issuer monitoring list |
| Market | FBIL G-sec par yields, USD/INR reference rate, bonds traded and face value per day |

## Development

```bash
npm ci
npm run dev       # http://localhost:5173
npm run lint
npm test
npm run build
```

Refresh the snapshot after a warehouse build with `python scripts/export_snapshot.py` from the repository root.

`PAGES_BASE` sets the base path for builds served from a subdirectory, as on GitHub Pages. In the Compose and Kubernetes deployments, nginx serves the build and routes `/api/<service>/` to the service APIs.
