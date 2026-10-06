/** "Acme Inc." → "acme-inc", matching identity-service's slug rule. */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    // "é" → "e" + accent mark; drop the mark instead of turning it into a dash.
    .replace(/\p{M}/gu, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63)
    .replace(/-+$/, '')
}
