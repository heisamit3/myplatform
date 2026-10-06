import { describe, expect, it } from 'vitest'
import { slugify } from './slug'

describe('slugify', () => {
  it.each([
    ['Acme Inc.', 'acme-inc'],
    ['  Café  Crème ', 'cafe-creme'],
    ['R&D -- Team 2', 'r-d-team-2'],
    ['!!!', ''],
  ])('%s → %s', (name, slug) => {
    expect(slugify(name)).toBe(slug)
  })

  it('stays within 63 characters and never ends with a dash', () => {
    const slug = slugify('a'.repeat(62) + ' b')
    expect(slug.length).toBeLessThanOrEqual(63)
    expect(slug.endsWith('-')).toBe(false)
  })
})
