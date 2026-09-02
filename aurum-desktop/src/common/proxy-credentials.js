import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);
const service = 'aurum-proxy-credentials';

export const proxyCredentialPersistenceAvailable = process.platform === 'darwin';

export async function loadProxyCredentials() {
  if (!proxyCredentialPersistenceAvailable) return null;
  try {
    const { stdout } = await execute('security', ['find-generic-password', '-s', service, '-w']);
    const value = JSON.parse(stdout.trim());
    return value?.username ? { username: String(value.username), password: String(value.password || '') } : null;
  } catch {
    return null;
  }
}

export async function saveProxyCredentials(credentials) {
  if (!proxyCredentialPersistenceAvailable) return false;
  const value = JSON.stringify({ username: String(credentials.username || ''), password: String(credentials.password || '') });
  await execute('security', ['add-generic-password', '-a', 'aurum', '-s', service, '-w', value, '-U']);
  return true;
}

export async function deleteProxyCredentials() {
  if (!proxyCredentialPersistenceAvailable) return;
  await execute('security', ['delete-generic-password', '-s', service]).catch(() => {});
}