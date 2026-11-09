import { describe, expect, it, vi } from 'vitest'
import { loadFeatured, renderFeatured, type FeaturedNote } from './featured'

const note: FeaturedNote = {
  title: 'Popular Sort Algorithms',
  url: 'https://notes.hien0ethan.com/popular-sort-algorithms/',
  date: '2020-10-17',
  category: 'tech',
  lang: 'vi',
  description: null,
}
const fallback = () => {
  document.body.innerHTML = '<ul id="f"><li>fallback</li></ul>'
  return document.getElementById('f')!
}

describe('featured notes', () => {
  it('renders title link, date and category', () => {
    const list = fallback()
    renderFeatured(list, [note])
    const a = list.querySelector('a')!
    expect(a.textContent).toBe('Popular Sort Algorithms')
    expect(a.href).toBe(note.url)
    expect(a.lang).toBe('vi')
    expect(list.querySelector('.meta')!.textContent).toBe('Oct 17, 2020 · tech')
  })

  it('never interprets titles as HTML', () => {
    const list = fallback()
    renderFeatured(list, [{ ...note, title: '<img src=x onerror=alert(1)>' }])
    expect(list.querySelector('img')).toBeNull()
    expect(list.querySelector('a')!.textContent).toBe('<img src=x onerror=alert(1)>')
  })

  it('keeps the fallback when the notes app is unreachable', async () => {
    const list = fallback()
    expect(await loadFeatured(list, vi.fn().mockRejectedValue(new TypeError('down')))).toBe(false)
    expect(await loadFeatured(list, vi.fn().mockResolvedValue(new Response('', { status: 502 })))).toBe(false)
    expect(await loadFeatured(list, vi.fn().mockResolvedValue(new Response('[]')))).toBe(false)
    expect(list.textContent).toBe('fallback')
  })

  it('replaces the fallback on success', async () => {
    const list = fallback()
    expect(await loadFeatured(list, vi.fn().mockResolvedValue(new Response(JSON.stringify([note]))))).toBe(true)
    expect(list.textContent).toContain('Popular Sort Algorithms')
  })
})
