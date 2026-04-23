import express from 'express';
import { createRouter } from './router';

const PORT = parseInt(process.env.PORT ?? '7007', 10);
const app = express();

app.use(express.json());
app.use('/api/gateforge', createRouter());

app.listen(PORT, '0.0.0.0', () => {
  console.log(`GateForge DevHub plugin listening on port ${PORT}`);
  console.log(`  POST /api/gateforge/migration-event`);
  console.log(`  GET  /api/gateforge/events`);
  console.log(`  GET  /api/gateforge/health`);
});
