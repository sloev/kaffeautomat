// Automat config.json: templates for new automats and the same basic checks the app does.

import kaffeautomat from '../../examples/kaffeautomat/config.json';
import aeg from '../../examples/aeg/config.json';
import test from '../../examples/test-uden-hardware/config.json';
import { HttpError } from './util.js';

export const TEMPLATES = {
  kaffeautomat,
  aeg,
  'test-uden-hardware': test,
};

const MAX_CONFIG_BYTES = 64 * 1024;

export function validateConfig(config) {
  if (!config || typeof config !== 'object' || Array.isArray(config)) throw new HttpError(400, 'config must be a JSON object');
  if (JSON.stringify(config).length > MAX_CONFIG_BYTES) throw new HttpError(413, 'config too large');
  const products = config.products;
  if (!Array.isArray(products) || products.length === 0) throw new HttpError(400, 'config.products must be a non-empty list');
  const ids = new Set();
  for (const p of products) {
    if (!p || typeof p.id !== 'string' || !p.id) throw new HttpError(400, 'every product needs an id');
    if (typeof p.name !== 'string' || !p.name) throw new HttpError(400, `product ${p.id}: name missing`);
    if (!Number.isInteger(p.priceOre) || p.priceOre <= 0) throw new HttpError(400, `product ${p.id}: priceOre must be a positive integer`);
    if (ids.has(p.id)) throw new HttpError(400, `product id ${p.id} is used twice`);
    ids.add(p.id);
  }
  // The server connection is set up by pairing, never by config.
  const { server, ...rest } = config;
  return rest;
}

export function configFromTemplate(template, name) {
  const base = TEMPLATES[template];
  if (!base) throw new HttpError(400, `unknown template; use one of: ${Object.keys(TEMPLATES).join(', ')}`);
  return { ...structuredClone(base), name };
}
