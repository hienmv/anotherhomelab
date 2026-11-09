/** Pure selection logic for /featured.json (kept free of astro:content so Vitest can import it). */
export interface FeaturedInput {
  id: string
  data: { title: string; date: Date; category: string; lang: string; description?: string; featured: boolean }
}

export interface FeaturedNote {
  title: string
  url: string
  date: string
  category: string
  lang: string
  description: string | null
}

/** Notes flagged `featured: true`, newest first; if none are flagged, the newest `fallback` notes. */
export function pickFeatured(notes: FeaturedInput[], site: string, fallback = 3): FeaturedNote[] {
  const byDate = [...notes].sort((a, b) => b.data.date.getTime() - a.data.date.getTime())
  const flagged = byDate.filter((n) => n.data.featured)
  return (flagged.length > 0 ? flagged : byDate.slice(0, fallback)).map((n) => ({
    title: n.data.title,
    url: new URL(`/${n.id}/`, site).toString(),
    date: n.data.date.toISOString().slice(0, 10),
    category: n.data.category,
    lang: n.data.lang,
    description: n.data.description ?? null,
  }))
}
