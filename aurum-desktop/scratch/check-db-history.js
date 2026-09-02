import { dbFile } from '../src/storage/history-db.js';
import { DatabaseSync } from 'node:sqlite';

const db = new DatabaseSync(dbFile);
const bullionRows = db.prepare('SELECT COUNT(*) as count FROM bullion_history').get();
const productRows = db.prepare('SELECT COUNT(*) as count FROM price_history').get();
console.log('Bullion history count:', bullionRows.count);
console.log('Price history count:', productRows.count);

const sampleBullion = db.prepare('SELECT * FROM bullion_history ORDER BY id DESC LIMIT 5').all();
console.log('Recent bullion history:', sampleBullion);

