import type { APIContext } from 'astro'
import { getCollection } from 'astro:content'
import { pickFeatured } from '../scripts/featured'

// Consumed by the landing page (hien0ethan.com), which proxies it from this app inside the cluster.
export async function GET(context: APIContext) {
  const notes = await getCollection('notes')
  return new Response(JSON.stringify(pickFeatured(notes, context.site!.toString())), {
    headers: { 'Content-Type': 'application/json' },
  })
}
