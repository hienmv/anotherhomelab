import { describe, expect, it } from 'vitest'
import { pickFeatured, type FeaturedInput } from './featured'

const note = (id: string, date: string, featured = false): FeaturedInput => ({
  id,
  data: { title: id.toUpperCase(), date: new Date(date), category: 'tech', lang: 'vi', featured },
})
const SITE = 'https://notes.hien0ethan.com'

describe('pickFeatured', () => {
  it('returns flagged notes newest first with absolute URLs', () => {
    const out = pickFeatured([note('a', '2020-01-01', true), note('b', '2021-01-01'), note('c', '2022-01-01', true)], SITE)
    expect(out.map((n) => n.url)).toEqual(['https://notes.hien0ethan.com/c/', 'https://notes.hien0ethan.com/a/'])
    expect(out[0]).toMatchObject({ title: 'C', date: '2022-01-01', category: 'tech', lang: 'vi', description: null })
  })

  it('falls back to the newest notes when none are flagged', () => {
    const out = pickFeatured([note('a', '2020-01-01'), note('b', '2021-01-01'), note('c', '2022-01-01'), note('d', '2019-01-01')], SITE)
    expect(out.map((n) => n.title)).toEqual(['C', 'B', 'A'])
  })
})
