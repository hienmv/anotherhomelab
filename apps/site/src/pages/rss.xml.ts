import rss from '@astrojs/rss'
import type { APIContext } from 'astro'
import profile from '../data/profile.json'
import { allNotes } from '../scripts/notes'

export async function GET(context: APIContext) {
  const notes = await allNotes()
  return rss({
    title: `${profile.siteTitle}: notes`,
    description: profile.tagline,
    site: context.site!,
    items: notes.map((n) => ({
      title: n.data.title,
      pubDate: n.data.date,
      description: n.data.description,
      link: `/notes/${n.id}/`,
      categories: [n.data.category],
    })),
  })
}
