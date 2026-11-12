/** Renders featured notes (from /featured.json, proxied from the notes app) into a list. */
export interface FeaturedNote {
  title: string
  url: string
  date: string
  category: string
  lang: string
  description: string | null
}

const fmt = (iso: string) =>
  new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' })

/** Builds DOM nodes with textContent only, so note titles can never inject markup. */
export function renderFeatured(list: HTMLElement, notes: FeaturedNote[]): void {
  list.replaceChildren(
    ...notes.map((n) => {
      const li = document.createElement('li')
      li.className = 'feature'
      const meta = document.createElement('p')
      meta.className = 'meta'
      meta.textContent = `${fmt(n.date)} · ${n.category}`
      const a = document.createElement('a')
      a.className = 'feature__title'
      a.href = n.url
      a.lang = n.lang
      a.textContent = n.title
      li.append(meta, a)
      if (n.description) {
        const p = document.createElement('p')
        p.className = 'muted'
        p.textContent = n.description
        li.append(p)
      }
      return li
    }),
  )
}

/** Loads and renders; on any failure leaves the server-rendered fallback ("Browse all notes"). */
export async function loadFeatured(list: HTMLElement, fetchImpl: typeof fetch = fetch): Promise<boolean> {
  try {
    const res = await fetchImpl('/featured.json', { headers: { Accept: 'application/json' } })
    if (!res.ok) return false
    const notes = (await res.json()) as FeaturedNote[]
    if (!Array.isArray(notes) || notes.length === 0) return false
    renderFeatured(list, notes)
    return true
  } catch {
    return false
  }
}
