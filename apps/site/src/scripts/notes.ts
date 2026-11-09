import { getCollection, type CollectionEntry } from 'astro:content'

export type Note = CollectionEntry<'notes'>

export async function allNotes(): Promise<Note[]> {
  return (await getCollection('notes')).sort((a, b) => b.data.date.getTime() - a.data.date.getTime())
}

export async function categories(): Promise<string[]> {
  return [...new Set((await allNotes()).map((n) => n.data.category))].sort()
}

export function readingMinutes(markdown: string | undefined): number {
  const words = (markdown ?? '').replace(/```[\s\S]*?```/g, ' ').split(/\s+/).filter(Boolean).length
  return Math.max(1, Math.round(words / 200))
}

export const formatDate = (d: Date) =>
  d.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'Asia/Tokyo' })
