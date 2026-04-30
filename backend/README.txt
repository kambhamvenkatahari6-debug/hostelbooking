Backend

Run:
- backend\run-backend.cmd
- backend\run-backend.ps1

The backend serves the frontend from ../frontend and exposes /api/* endpoints.
Student/bookings/leaves data is stored in backend/data/store.bin.

Render deploy:
- render.yaml is included at project root.
- Create a new Blueprint service in Render from this repo.
- Health endpoint: /login.html
